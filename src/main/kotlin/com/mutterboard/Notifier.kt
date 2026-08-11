package com.mutterboard

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

/**
 * Desktop notifications over org.freedesktop.Notifications, the same D-Bus
 * route the tray icon already takes.
 *
 * This exists because the app is driven from the tray: when a dictation fails,
 * the error lands in a settings window the user almost certainly doesn't have
 * open, so from where they're sitting nothing happened at all. A failure has to
 * reach them where they are, and it has to say the recording was kept.
 *
 * Best-effort throughout — a desktop without a notification daemon just
 * doesn't get these.
 */
object Notifier {

    @DBusInterfaceName("org.freedesktop.Notifications")
    private interface Notifications : DBusInterface {
        @Suppress("LongParameterList")
        fun Notify(
            appName: String,
            replacesId: UInt32,
            appIcon: String,
            summary: String,
            body: String,
            actions: List<String>,
            hints: Map<String, Variant<*>>,
            expireTimeout: Int,
        ): UInt32
    }

    private const val ICON = "io.github.ryskeel.Mutterboard"

    @Volatile private var conn: DBusConnection? = null
    /** Reusing the id makes repeat failures replace each other instead of stacking. */
    @Volatile private var lastId = UInt32(0)

    /**
     * Show [summary] with [body]. Sent on a daemon thread so a slow or missing
     * notification service can never stall a dictation.
     */
    fun notify(summary: String, body: String) {
        Thread {
            runCatching {
                val c = conn ?: DBusConnectionBuilder.forSessionBus().build().also { conn = it }
                lastId = c.getRemoteObject(
                    "org.freedesktop.Notifications",
                    "/org/freedesktop/Notifications",
                    Notifications::class.java,
                ).Notify(
                    "Mutterboard",
                    lastId,
                    ICON,
                    summary,
                    body,
                    emptyList(),
                    mapOf("desktop-entry" to Variant(ICON)),
                    8_000,
                )
            }.onFailure { println("[notify] $summary — unavailable (${it.message})") }
        }.apply { isDaemon = true; name = "notify" }.start()
    }

    fun close() {
        runCatching { conn?.disconnect() }
        conn = null
    }
}
