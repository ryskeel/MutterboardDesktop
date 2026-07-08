package com.mutterboard

import java.io.File
import java.util.Properties

/**
 * Persists the Groq API key and custom vocabulary to a plain properties file
 * under the user's XDG config dir (~/.config/mutterboard/settings.properties).
 * The desktop analogue of the Android app's SharedPreferences.
 *
 * The key is the user's own Groq key, entered in the app — nothing is bundled.
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

    /** Where recorded WAVs are staged before upload. */
    val cacheDir: File = File(configDir, "cache")

    var apiKey: String
        get() = props.getProperty("apiKey", "")
        set(value) { props.setProperty("apiKey", value); save() }

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

    /** Transcribe + clean up locally (Parakeet + Qwen3) instead of via Groq. */
    var offlineMode: Boolean
        get() = props.getProperty("offlineMode", "false").toBoolean()
        set(value) { props.setProperty("offlineMode", value.toString()); save() }

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

    private fun save() {
        configDir.mkdirs()
        file.outputStream().use { props.store(it, "Mutterboard desktop settings") }
    }
}
