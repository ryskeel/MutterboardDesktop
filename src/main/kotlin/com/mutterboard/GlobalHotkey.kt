package com.mutterboard

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt64
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Registers a system-wide dictation shortcut through the XDG GlobalShortcuts
 * portal (org.freedesktop.portal.GlobalShortcuts) over D-Bus. This is the only
 * sanctioned way to get a global hotkey on Wayland: the app requests a shortcut
 * with a preferred trigger, the compositor (KWin) shows a one-time approval
 * dialog and owns the actual binding, then emits an Activated signal each time
 * the key is pressed. The user can also rebind it in KDE System Settings.
 *
 * All portal calls are async via the Request/Response object pattern: a method
 * returns immediately with a request path, and the real result arrives later as
 * a Response signal on that path (BindShortcuts blocks on the user's dialog).
 */
class GlobalHotkey(private val onActivated: () -> Unit) {

    @DBusInterfaceName("org.freedesktop.portal.GlobalShortcuts")
    interface Portal : DBusInterface {
        fun CreateSession(options: Map<String, Variant<*>>): DBusPath
        fun BindShortcuts(
            sessionHandle: DBusPath,
            shortcuts: List<ShortcutTuple>,
            parentWindow: String,
            options: Map<String, Variant<*>>,
        ): DBusPath

        class Activated(
            path: String,
            @JvmField val sessionHandle: DBusPath,
            @JvmField val shortcutId: String,
            @JvmField val timestamp: UInt64,
            @JvmField val options: Map<String, Variant<*>>,
        ) : DBusSignal(path, sessionHandle, shortcutId, timestamp, options)
    }

    /**
     * KDE's global-shortcut service. The portal treats the app's trigger only
     * as a first-time hint — after that KDE keeps its own stored binding — so
     * in-app rebinding goes straight to kglobalaccel, the same API KDE System
     * Settings uses. The change applies immediately, no dialog.
     */
    @DBusInterfaceName("org.kde.KGlobalAccel")
    interface KGlobalAccel : DBusInterface {
        fun allMainComponents(): List<List<String>>
        fun allActionsForComponent(actionId: List<String>): List<List<String>>
        fun setForeignShortcut(actionId: List<String>, keys: List<Int>)
    }

    @DBusInterfaceName("org.freedesktop.portal.Request")
    interface Request : DBusInterface {
        class Response(
            path: String,
            @JvmField val response: org.freedesktop.dbus.types.UInt32,
            @JvmField val results: Map<String, Variant<*>>,
        ) : DBusSignal(path, response, results)
    }

    class ShortcutTuple(
        @field:Position(0) val id: String,
        @field:Position(1) val props: Map<String, Variant<*>>,
    ) : Struct()

    @Volatile private var conn: DBusConnection? = null
    private val tokens = AtomicInteger()

    /**
     * Register (or re-register) the shortcut with [combo] like "Ctrl+Space".
     * Runs on a background thread; [onStatus] reports progress for the UI.
     */
    fun register(combo: String, onStatus: (String) -> Unit) {
        val status: (String) -> Unit = { println("[hotkey] $it"); onStatus(it) }
        Thread {
            try {
                val c = conn ?: DBusConnectionBuilder.forSessionBus().build().also { conn = it }
                val portal = c.getRemoteObject(
                    "org.freedesktop.portal.Desktop",
                    "/org/freedesktop/portal/desktop",
                    Portal::class.java,
                )

                // Each key press emits Activated; toggle on press (ignore release).
                c.addSigHandler(Portal.Activated::class.java, DBusSigHandler { sig ->
                    println("[hotkey] Activated: ${sig.shortcutId}")
                    if (sig.shortcutId == SHORTCUT_ID) onActivated()
                })

                val sessionToken = "mb_sess_${tokens.incrementAndGet()}"
                val createToken = "mb_${tokens.incrementAndGet()}"
                val createResult = portalRequest(c, createToken) {
                    portal.CreateSession(
                        mapOf(
                            "handle_token" to Variant(createToken),
                            "session_handle_token" to Variant(sessionToken),
                        ),
                    )
                } ?: run { status("Shortcut setup failed (no portal response)"); return@Thread }

                val sessionHandle = (createResult["session_handle"]?.value as? String)
                    ?: run { status("Shortcut setup failed (no session)"); return@Thread }

                val trigger = comboToTrigger(combo)
                val shortcut = ShortcutTuple(
                    SHORTCUT_ID,
                    mapOf(
                        "description" to Variant("Start / stop Mutterboard dictation"),
                        "preferred_trigger" to Variant(trigger),
                    ),
                )
                val bindToken = "mb_${tokens.incrementAndGet()}"
                status("Approve the shortcut in the KDE dialog…")
                portalRequest(c, bindToken) {
                    portal.BindShortcuts(
                        DBusPath(sessionHandle),
                        listOf(shortcut),
                        "",
                        mapOf("handle_token" to Variant(bindToken)),
                    )
                } ?: run { status("Shortcut not confirmed"); return@Thread }

                status("Shortcut active — $combo")
            } catch (e: Exception) {
                status("Shortcut error: ${e.message}")
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Rebind the already-registered shortcut to [combo], effective immediately.
     * The action can live under more than one kglobalaccel component (launching
     * from a terminal during development registers it under the terminal's app
     * id), so every component carrying our action id is updated.
     */
    fun rebind(combo: String, onStatus: (String) -> Unit) {
        val status: (String) -> Unit = { println("[hotkey] $it"); onStatus(it) }
        Thread {
            try {
                val qtKey = comboToQtKey(combo)
                if (qtKey == null) {
                    status("Can't bind \"$combo\" — try another combo")
                    return@Thread
                }
                val c = conn ?: DBusConnectionBuilder.forSessionBus().build().also { conn = it }
                val kga = c.getRemoteObject(
                    "org.kde.kglobalaccel",
                    "/kglobalaccel",
                    KGlobalAccel::class.java,
                )
                var bound = 0
                for (component in kga.allMainComponents()) {
                    val unique = component.getOrNull(0) ?: continue
                    val actions = runCatching {
                        kga.allActionsForComponent(listOf(unique, "", "", ""))
                    }.getOrNull() ?: continue
                    if (actions.any { it.getOrNull(1) == SHORTCUT_ID }) {
                        kga.setForeignShortcut(listOf(unique, SHORTCUT_ID, "", ""), listOf(qtKey))
                        bound++
                    }
                }
                if (bound > 0) status("Shortcut active — $combo")
                else status("Shortcut saved — applies on next launch")
            } catch (e: Exception) {
                status("Rebind failed: ${e.message}")
            }
        }.apply { isDaemon = true }.start()
    }

    /** Fire a portal method and block until its Response signal arrives. */
    private fun portalRequest(
        c: DBusConnection,
        token: String,
        call: () -> DBusPath,
    ): Map<String, Variant<*>>? {
        val sender = c.uniqueName.removePrefix(":").replace(".", "_")
        val expected = "/org/freedesktop/portal/desktop/request/$sender/$token"
        val latch = CountDownLatch(1)
        val ref = AtomicReference<Map<String, Variant<*>>?>(null)
        val handler = DBusSigHandler<Request.Response> { sig ->
            if (sig.path == expected) {
                println("[hotkey] response code=${sig.response} results=${sig.results.keys}")
                // response 0 = success; 1 = user cancelled; 2 = ended some other way.
                ref.set(if (sig.response.toInt() == 0) sig.results else null)
                latch.countDown()
            }
        }
        c.addSigHandler(Request.Response::class.java, handler)
        try {
            call()
            latch.await(120, TimeUnit.SECONDS) // long: BindShortcuts waits on the user's dialog
        } finally {
            c.removeSigHandler(Request.Response::class.java, handler)
        }
        return ref.get()
    }

    fun close() {
        try { conn?.disconnect() } catch (_: Exception) {}
        conn = null
    }

    companion object {
        private const val SHORTCUT_ID = "dictation"

        /** "Ctrl+Space" -> "CTRL+space" per the XDG shortcuts trigger format. */
        private fun comboToTrigger(combo: String): String =
            combo.split("+").joinToString("+") { part ->
                when (part.trim().lowercase()) {
                    "ctrl", "control" -> "CTRL"
                    "alt" -> "ALT"
                    "shift" -> "SHIFT"
                    "super", "meta", "win" -> "LOGO"
                    "space" -> "space"
                    else -> part.trim().lowercase()
                }
            }

        private val QT_MOD_BITS = mapOf(
            "ctrl" to 0x04000000, "control" to 0x04000000,
            "shift" to 0x02000000,
            "alt" to 0x08000000,
            "super" to 0x10000000, "meta" to 0x10000000, "win" to 0x10000000,
        )

        // Qt::Key codes for the modifier keys themselves, for combos with no
        // regular key in them.
        private val QT_MOD_KEYS = mapOf(
            "shift" to 0x01000020,
            "ctrl" to 0x01000021, "control" to 0x01000021,
            "super" to 0x01000022, "meta" to 0x01000022, "win" to 0x01000022,
            "alt" to 0x01000023,
        )

        /** "Ctrl+Alt+D" -> the Qt modifier|key int that kglobalaccel stores. */
        private fun comboToQtKey(combo: String): Int? {
            val parts = combo.split("+").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            var modifiers = 0
            var key: Int? = null
            for (part in parts) {
                val bits = QT_MOD_BITS[part.lowercase()]
                if (bits != null) modifiers = modifiers or bits
                else key = qtKeyFor(part) ?: return null
            }
            if (key == null) {
                // Modifier-only combo (e.g. Ctrl+Shift): encode the last
                // modifier as the key. Best effort — KDE stores it, though not
                // every such combo triggers reliably.
                val last = parts.last().lowercase()
                key = QT_MOD_KEYS[last] ?: return null
                modifiers = modifiers and (QT_MOD_BITS[last] ?: 0).inv()
            }
            return modifiers or key
        }

        /** Maps an AWT key name (from KeyEvent.getKeyText) to a Qt::Key code. */
        private fun qtKeyFor(name: String): Int? {
            val n = name.trim()
            if (n.length == 1) {
                val c = n.uppercase()[0]
                // Letters, digits, and printable punctuation are their own codes.
                if (c.code in 0x20..0x7E) return c.code
            }
            return when (n.lowercase()) {
                "space" -> 0x20
                "minus" -> '-'.code
                "equals" -> '='.code
                "comma" -> ','.code
                "period" -> '.'.code
                "slash" -> '/'.code
                "semicolon" -> ';'.code
                "quote" -> '\''.code
                "back quote" -> '`'.code
                "open bracket" -> '['.code
                "close bracket" -> ']'.code
                "back slash" -> '\\'.code
                "escape" -> 0x01000000
                "tab" -> 0x01000001
                "backspace" -> 0x01000003
                "enter", "return" -> 0x01000004
                "insert" -> 0x01000006
                "delete" -> 0x01000007
                "pause" -> 0x01000008
                "print screen" -> 0x01000009
                "home" -> 0x01000010
                "end" -> 0x01000011
                "left" -> 0x01000012
                "up" -> 0x01000013
                "right" -> 0x01000014
                "down" -> 0x01000015
                "page up" -> 0x01000016
                "page down" -> 0x01000017
                "caps lock" -> 0x01000024
                "num lock" -> 0x01000025
                "scroll lock" -> 0x01000026
                else ->
                    if (n.length in 2..3 && (n[0] == 'F' || n[0] == 'f')) {
                        n.drop(1).toIntOrNull()
                            ?.takeIf { it in 1..35 }
                            ?.let { 0x01000030 + it - 1 }
                    } else null
            }
        }
    }
}
