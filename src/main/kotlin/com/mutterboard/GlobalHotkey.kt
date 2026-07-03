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
    }
}
