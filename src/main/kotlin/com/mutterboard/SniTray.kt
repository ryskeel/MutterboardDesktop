package com.mutterboard

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import kotlin.math.max

/**
 * Tray icon speaking Plasma's native StatusNotifierItem protocol over D-Bus,
 * with a com.canonical.dbusmenu sidecar for the right-click menu.
 *
 * AWT's SystemTray reaches a Wayland Plasma panel through XWayland + XEmbed,
 * where alpha is lost (the icon gets an opaque white box) and clicks are
 * unreliable. SNI is what native apps use: the icon travels as ARGB pixmaps
 * with real transparency, sized exactly for the panel, and clicks arrive as
 * D-Bus method calls. The caller falls back to the AWT tray when no
 * StatusNotifierWatcher is on the bus (non-Plasma desktops).
 */
class SniTray(
    private val onToggle: () -> Unit,
    private val onToggleModel: () -> Unit,
    private val onSettings: () -> Unit,
    private val onQuit: () -> Unit,
) {

    @DBusInterfaceName("org.kde.StatusNotifierWatcher")
    interface Watcher : DBusInterface {
        fun RegisterStatusNotifierItem(service: String)
    }

    @DBusInterfaceName("org.kde.StatusNotifierItem")
    interface Item : DBusInterface {
        fun Activate(x: Int, y: Int)
        fun SecondaryActivate(x: Int, y: Int)
        fun ContextMenu(x: Int, y: Int)
        fun Scroll(delta: Int, orientation: String)

        class NewIcon(path: String) : DBusSignal(path)
        class NewToolTip(path: String) : DBusSignal(path)
    }

    /** One icon bitmap: width, height, raw ARGB32 in network byte order. */
    class Pixmap(
        @field:Position(0) val width: Int,
        @field:Position(1) val height: Int,
        @field:Position(2) val data: ByteArray,
    ) : Struct()

    class ToolTip(
        @field:Position(0) val iconName: String,
        @field:Position(1) val iconPixmaps: List<Pixmap>,
        @field:Position(2) val title: String,
        @field:Position(3) val description: String,
    ) : Struct()

    class MenuNode(
        @field:Position(0) val id: Int,
        @field:Position(1) val properties: Map<String, Variant<*>>,
        @field:Position(2) val children: List<Variant<*>>,
    ) : Struct()

    // dbus-java requires Tuple subclasses to be generic: the wire signature of
    // a multi-value return is derived from the method's parameterized type.
    class LayoutResult<A, B>(
        @field:Position(0) val revision: A,
        @field:Position(1) val layout: B,
    ) : Tuple()

    class ItemProperties(
        @field:Position(0) val id: Int,
        @field:Position(1) val properties: Map<String, Variant<*>>,
    ) : Struct()

    /** One entry of an EventGroup batch: (isvu), same fields as Event's arguments. */
    class MenuEvent(
        @field:Position(0) val id: Int,
        @field:Position(1) val eventId: String,
        @field:Position(2) val data: Variant<*>,
        @field:Position(3) val timestamp: UInt32,
    ) : Struct()

    class AboutToShowGroupResult<A, B>(
        @field:Position(0) val updatesNeeded: A,
        @field:Position(1) val idErrors: B,
    ) : Tuple()

    @DBusInterfaceName("com.canonical.dbusmenu")
    interface Menu : DBusInterface {
        fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): LayoutResult<UInt32, MenuNode>
        fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<ItemProperties>
        fun GetProperty(id: Int, name: String): Variant<*>
        fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32)
        fun AboutToShow(id: Int): Boolean
        // The batched forms. Plasma sends Event/AboutToShow, but libdbusmenu
        // hosts (Cinnamon's xapp-sn-watcher, likely other GTK panels) only
        // send these — without them every menu click is silently dropped.
        fun EventGroup(events: List<MenuEvent>): List<Int>
        fun AboutToShowGroup(ids: List<Int>): AboutToShowGroupResult<List<Int>, List<Int>>

        class LayoutUpdated(
            path: String,
            @JvmField val revision: UInt32,
            @JvmField val parent: Int,
        ) : DBusSignal(path, revision, parent)
    }

    @Volatile private var conn: DBusConnection? = null
    @Volatile private var recording = false
    @Volatile private var transcribing = false
    @Volatile private var modelLoaded = false
    @Volatile private var statusText = "idle"
    @Volatile private var revision = 1

    private val item = ItemObject()
    private val menu = MenuObject()

    /**
     * Connect, export the item + menu objects, and register with the watcher.
     * Runs on a background thread; [onReady] gets false when Plasma's watcher
     * is missing so the caller can fall back to the AWT tray.
     */
    fun start(onReady: (Boolean) -> Unit) {
        Thread {
            try {
                val c = DBusConnectionBuilder.forSessionBus().build()
                conn = c
                c.exportObject(ITEM_PATH, item)
                c.exportObject(MENU_PATH, menu)
                // Registering by object path (not a requested bus name) makes
                // the watcher track us via this connection's unique name.
                c.getRemoteObject(
                    "org.kde.StatusNotifierWatcher",
                    "/StatusNotifierWatcher",
                    Watcher::class.java,
                ).RegisterStatusNotifierItem(ITEM_PATH)
                println("[tray] registered SNI as ${c.uniqueName}$ITEM_PATH")
                onReady(true)
            } catch (e: Exception) {
                println("[tray] SNI unavailable (${e.message}); caller should fall back to AWT tray")
                try { conn?.disconnect() } catch (_: Exception) {}
                conn = null
                onReady(false)
            }
        }.apply { isDaemon = true; name = "sni-tray" }.start()
    }

    /** Reflect a status change: icon color, tooltip, and the menu's toggle labels. */
    fun update(
        recording: Boolean,
        transcribing: Boolean,
        modelLoaded: Boolean,
        statusText: String,
    ) {
        this.recording = recording
        this.transcribing = transcribing
        this.modelLoaded = modelLoaded
        this.statusText = statusText
        revision++
        val c = conn ?: return
        try {
            c.sendMessage(Item.NewIcon(ITEM_PATH))
            c.sendMessage(Item.NewToolTip(ITEM_PATH))
            c.sendMessage(Menu.LayoutUpdated(MENU_PATH, UInt32(revision.toLong()), 0))
        } catch (e: Exception) {
            println("[tray] update failed: ${e.message}")
        }
    }

    fun close() {
        try { conn?.disconnect() } catch (_: Exception) {}
        conn = null
    }

    // ---- Icon rendering ----------------------------------------------------

    /**
     * The brand squiggle, drawn fresh at each panel size so the stroke stays
     * crisp (scaling one big PNG blurs it). Slightly heavier than the header
     * mark's so it holds up at 22px.
     */
    private fun renderIcon(size: Int, color: Color): Pixmap {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.color = color
        val w = size * 0.84f
        val x0 = (size - w) / 2f
        val path = Path2D.Float()
        Squiggle.points(x0, x0 + w, size / 2f).forEachIndexed { i, (x, y) ->
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        g.stroke = BasicStroke(
            max(2f, Squiggle.stroke(w) * 1.25f),
            BasicStroke.CAP_ROUND,
            BasicStroke.JOIN_ROUND,
        )
        g.draw(path)
        g.dispose()

        val data = ByteArray(size * size * 4)
        var p = 0
        for (yy in 0 until size) {
            for (xx in 0 until size) {
                val v = img.getRGB(xx, yy)
                data[p++] = (v ushr 24).toByte()
                data[p++] = (v ushr 16).toByte()
                data[p++] = (v ushr 8).toByte()
                data[p++] = v.toByte()
            }
        }
        return Pixmap(size, size, data)
    }

    private fun pixmaps(): List<Pixmap> {
        // Red while listening, orange while transcribing — so the wait between
        // "stopped talking" and "text pasted" is visibly the app thinking.
        val color = when {
            recording -> Color(0xEF, 0x53, 0x50)
            transcribing -> Color(0xFF, 0xA7, 0x26)
            else -> Color.WHITE
        }
        return listOf(22, 24, 32, 48, 64).map { renderIcon(it, color) }
    }

    // ---- Exported D-Bus objects ---------------------------------------------

    private inner class ItemObject : Item, Properties {
        override fun getObjectPath() = ITEM_PATH
        override fun isRemote() = false

        override fun Activate(x: Int, y: Int) = onToggle()
        override fun SecondaryActivate(x: Int, y: Int) = onToggle()
        override fun ContextMenu(x: Int, y: Int) {} // Plasma renders /MenuBar itself
        override fun Scroll(delta: Int, orientation: String) {}

        private fun props(): Map<String, Variant<*>> = mapOf(
            "Category" to Variant("ApplicationStatus"),
            "Id" to Variant("mutterboard"),
            "Title" to Variant("Mutterboard"),
            "Status" to Variant("Active"),
            "WindowId" to Variant(0),
            "IconName" to Variant(""),
            "IconThemePath" to Variant(""),
            "IconPixmap" to Variant(pixmaps(), "a(iiay)"),
            "OverlayIconName" to Variant(""),
            "OverlayIconPixmap" to Variant(emptyList<Pixmap>(), "a(iiay)"),
            "AttentionIconName" to Variant(""),
            "AttentionIconPixmap" to Variant(emptyList<Pixmap>(), "a(iiay)"),
            "ToolTip" to Variant(
                ToolTip("", emptyList(), "Mutterboard", statusText),
                "(sa(iiay)ss)",
            ),
            "ItemIsMenu" to Variant(false),
            "Menu" to Variant(DBusPath(MENU_PATH)),
        )

        // Return the Variant itself: dbus-java passes a pre-qualified Variant
        // through as the 'v' reply, but cannot infer signatures (e.g. a(iiay))
        // from raw values, and Plasma refetches single properties via Get.
        @Suppress("UNCHECKED_CAST")
        override fun <A> Get(interfaceName: String, propertyName: String): A =
            props()[propertyName] as A

        override fun <A> Set(interfaceName: String, propertyName: String, value: A) {}

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = props()
    }

    private inner class MenuObject : Menu, Properties {
        override fun getObjectPath() = MENU_PATH
        override fun isRemote() = false

        // Fixed ids: 0 root, 1 record/stop toggle, 2 model load/unload,
        // 3 separator, 4 settings, 5 quit.
        private fun itemProps(id: Int): Map<String, Variant<*>> = when (id) {
            0 -> mapOf("children-display" to Variant("submenu"))
            1 -> mapOf(
                "label" to Variant(if (recording) "Stop & transcribe" else "Record"),
                "enabled" to Variant(true),
                "visible" to Variant(true),
            )
            // Greyed out mid-dictation: unloading the engine under a take in
            // flight is a recording lost, which is the one thing that mustn't
            // happen.
            2 -> mapOf(
                "label" to Variant(if (modelLoaded) "Unload model (free VRAM)" else "Load model"),
                "enabled" to Variant(!recording && !transcribing),
                "visible" to Variant(true),
            )
            3 -> mapOf("type" to Variant("separator"), "visible" to Variant(true))
            4 -> mapOf(
                "label" to Variant("Settings"),
                "enabled" to Variant(true),
                "visible" to Variant(true),
            )
            5 -> mapOf(
                "label" to Variant("Quit"),
                "enabled" to Variant(true),
                "visible" to Variant(true),
            )
            else -> emptyMap()
        }

        private fun node(id: Int, children: List<Int>): MenuNode = MenuNode(
            id,
            itemProps(id),
            children.map { Variant(node(it, emptyList()), "(ia{sv}av)") },
        )

        override fun GetLayout(
            parentId: Int,
            recursionDepth: Int,
            propertyNames: List<String>,
        ): LayoutResult<UInt32, MenuNode> {
            val root =
                if (parentId == 0) node(0, listOf(1, 2, 3, 4, 5)) else node(parentId, emptyList())
            return LayoutResult(UInt32(revision.toLong()), root)
        }

        override fun GetGroupProperties(
            ids: List<Int>,
            propertyNames: List<String>,
        ): List<ItemProperties> = ids.map { ItemProperties(it, itemProps(it)) }

        override fun GetProperty(id: Int, name: String): Variant<*> =
            itemProps(id)[name] ?: Variant("")

        override fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) {
            if (eventId != "clicked") return
            when (id) {
                1 -> onToggle()
                2 -> if (!recording && !transcribing) onToggleModel()
                4 -> onSettings()
                5 -> onQuit()
            }
        }

        override fun AboutToShow(id: Int): Boolean = false

        /** Returns the ids that don't exist, per the dbusmenu spec. */
        override fun EventGroup(events: List<MenuEvent>): List<Int> {
            events.forEach { Event(it.id, it.eventId, it.data, it.timestamp) }
            return events.map { it.id }.filterNot { it in 0..5 }
        }

        override fun AboutToShowGroup(ids: List<Int>): AboutToShowGroupResult<List<Int>, List<Int>> =
            AboutToShowGroupResult(emptyList(), ids.filterNot { it in 0..5 })

        private fun props(): Map<String, Variant<*>> = mapOf(
            "Version" to Variant(UInt32(3)),
            "TextDirection" to Variant("ltr"),
            "Status" to Variant("normal"),
            "IconThemePath" to Variant(emptyList<String>(), "as"),
        )

        // Return the Variant itself: dbus-java passes a pre-qualified Variant
        // through as the 'v' reply, but cannot infer signatures (e.g. a(iiay))
        // from raw values, and Plasma refetches single properties via Get.
        @Suppress("UNCHECKED_CAST")
        override fun <A> Get(interfaceName: String, propertyName: String): A =
            props()[propertyName] as A

        override fun <A> Set(interfaceName: String, propertyName: String, value: A) {}

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = props()
    }

    companion object {
        private const val ITEM_PATH = "/StatusNotifierItem"
        private const val MENU_PATH = "/MenuBar"
    }
}
