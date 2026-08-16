package com.mutterboard

import java.io.File
import java.util.Properties

/**
 * Persists preferences to a plain properties file under the user's XDG config
 * dir (~/.config/mutterboard/settings.properties). The desktop analogue of the
 * Android app's SharedPreferences.
 *
 * No API key lives here any more: dictation runs entirely on this machine, so
 * there is no service to authenticate to. A key left over from an older version
 * is simply ignored rather than migrated away.
 */
class Settings {
    private val configDir: File = run {
        val xdg = System.getenv("XDG_CONFIG_HOME")
        val base = if (!xdg.isNullOrBlank()) File(xdg) else File(System.getProperty("user.home"), ".config")
        File(base, "mutterboard")
    }
    private val file = File(configDir, "settings.properties")
    private val props = Properties().apply {
        if (file.exists()) file.inputStream().use { load(it) }
    }

    /** Where recorded WAVs are staged for the local recognizer. */
    val cacheDir: File = File(configDir, "cache")

    var vocabulary: String
        get() = props.getProperty("vocabulary", "")
        set(value) { props.setProperty("vocabulary", value); save() }

    /**
     * The dictation shortcut, stored as a human-readable combo like "Ctrl+Alt+D".
     * The setting persists now; global capture of it is wired up with the
     * OS-level hotkey work (it does not yet trigger dictation while other apps
     * are focused).
     */
    var shortcut: String
        get() = props.getProperty("shortcut", "Ctrl+Space")
        set(value) { props.setProperty("shortcut", value); save() }

    /** Play the start/stop pop cues when recording toggles. */
    var soundEnabled: Boolean
        get() = props.getProperty("soundEnabled", "true").toBoolean()
        set(value) { props.setProperty("soundEnabled", value.toString()); save() }

    /** Cue volume, linear 0..1. */
    var soundVolume: Float
        get() = props.getProperty("soundVolume", "0.7").toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.7f
        set(value) { props.setProperty("soundVolume", value.coerceIn(0f, 1f).toString()); save() }

    /** Start Mutterboard (hidden, tray only) at login. */
    var launchAtStartup: Boolean
        get() = props.getProperty("launchAtStartup", "true").toBoolean()
        set(value) { props.setProperty("launchAtStartup", value.toString()); save() }

    /**
     * How many dictations to keep, audio and all. 0 means no limit; pruning
     * then rests on [historyMaxDays] alone.
     */
    var historyMaxTakes: Int
        get() = props.getProperty("historyMaxTakes", "200").toIntOrNull() ?: 200
        set(value) { props.setProperty("historyMaxTakes", value.toString()); save() }

    /** Days of history to keep. 0 means forever. */
    var historyMaxDays: Int
        get() = props.getProperty("historyMaxDays", "30").toIntOrNull() ?: 30
        set(value) { props.setProperty("historyMaxDays", value.toString()); save() }

    private fun save() {
        configDir.mkdirs()
        file.outputStream().use { props.store(it, "Mutterboard desktop settings") }
    }
}
