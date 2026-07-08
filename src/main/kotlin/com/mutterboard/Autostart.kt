package com.mutterboard

import java.io.File

/**
 * Launch-at-login via an XDG autostart entry (~/.config/autostart), the
 * standard KDE/GNOME mechanism. The entry starts the app with --hidden so it
 * comes up in the tray with the global shortcut live, but without popping the
 * settings window.
 */
object Autostart {
    private val entry: File = run {
        val xdg = System.getenv("XDG_CONFIG_HOME")
        val base = if (!xdg.isNullOrBlank()) File(xdg) else File(System.getProperty("user.home"), ".config")
        File(File(base, "autostart"), "mutterboard.desktop")
    }

    /** Creates or removes the autostart entry to match the setting. */
    fun sync(enabled: Boolean) {
        runCatching {
            if (!enabled) {
                entry.delete()
                return
            }
            entry.parentFile.mkdirs()
            entry.writeText(
                """
                [Desktop Entry]
                Type=Application
                Name=Mutterboard
                Comment=Voice dictation, started in the tray
                Exec=${execLine()}
                Icon=${iconRef()}
                Terminal=false
                StartupNotify=false
                X-GNOME-Autostart-enabled=true
                """.trimIndent() + "\n"
            )
        }.onFailure { println("[autostart] sync failed: ${it.message}") }
    }

    /**
     * The command to autostart. Inside Flatpak the sandboxed binary path means
     * nothing to the host session, so the entry must go through `flatpak run`;
     * the sandbox needs --filesystem=xdg-config/autostart for the write.
     */
    private fun execLine(): String {
        val flatpakId = System.getenv("FLATPAK_ID")
        if (!flatpakId.isNullOrBlank()) return "flatpak run $flatpakId --hidden"
        return "${executable()} --hidden"
    }

    /** Icon reference: the exported theme icon under Flatpak, a file path otherwise. */
    private fun iconRef(): String {
        val flatpakId = System.getenv("FLATPAK_ID")
        if (!flatpakId.isNullOrBlank()) return flatpakId
        return File(installDir(), "mutterboard-icon.png").absolutePath
    }

    /**
     * The binary to autostart. Prefer the running process's own launcher when
     * it is the packaged app; fall back to the installDesktop location so a
     * dev run (gradle's java process) still writes a useful entry.
     */
    private fun executable(): String {
        val self = ProcessHandle.current().info().command().orElse("")
        if (self.endsWith("/Mutterboard")) return self
        return File(installDir(), "bin/Mutterboard").absolutePath
    }

    private fun installDir(): File =
        File(System.getProperty("user.home"), ".local/opt/Mutterboard")
}
