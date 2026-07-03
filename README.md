# Mutterboard Desktop

A desktop port of the Mutterboard dictation app for Linux (starting with Bazzite/KDE),
built with **Compose Multiplatform**. It reuses the Android app's exact transcription
pipeline: **Groq `whisper-large-v3-turbo`** for speech-to-text, followed by the
**`llama-3.3-70b-versatile`** refiner (same prompt) that makes the output "just right".

## Status — v0.1 (walking skeleton)

Working:
- Compose window in the Mutterboard color scheme (light-peach / monochrome, dark-mode aware).
- Settings: Groq API key (stored locally) + custom vocabulary.
- **Record** button → captures the mic (16 kHz mono WAV via `javax.sound.sampled`)
  → Whisper → refiner → result **copied to the clipboard** and shown in the window.
- Equalizer level meter while recording.
- System tray icon (open / record / quit); closing the window keeps it running in the tray.

Not yet (planned v0.2 — the Wayland-specific pieces that need prototyping):
- Global hotkey to start/stop dictation (KDE `GlobalShortcuts` portal).
- Floating, movable "pill" capture UI.
- Auto-paste into the focused app (single Ctrl+V, not character-by-character typing).
- On-device Parakeet engine (the Android "Offline" option).
- Flatpak packaging for distribution.

## Running it

Requires a JDK 17+. This project is configured to use Android Studio's bundled JDK 21
(see `org.gradle.java.home` in `gradle.properties`) so nothing extra is needed on this
machine. Gradle itself still needs a JVM to launch, so set `JAVA_HOME` when invoking:

```bash
export JAVA_HOME=/var/home/rkeel/Applications/android-studio/jbr
./gradlew run
```

A window opens. Paste your Groq API key into Settings, click **Record**, speak, click
**Stop & transcribe** — the cleaned-up text lands on your clipboard.

To stop everything (the `run` task keeps a Gradle daemon alive):

```bash
./gradlew --stop
```

## Packaging (local test builds)

```bash
./gradlew packageDeb        # .deb for Debian/Ubuntu
./gradlew packageAppImage   # self-contained image
```

(Flatpak — the right format for Bazzite — comes in a later step.)

## Layout

```
src/main/kotlin/com/mutterboard/
  Main.kt              window + tray + record→transcribe→refine→clipboard controller
  Theme.kt             Mutterboard palette, ported from the Android app
  Transcriber.kt       transcription interface
  GroqWhisperClient.kt Whisper V3 Turbo call (ported)
  GroqRefiner.kt       Llama refiner + prompt + invention guard (ported verbatim)
  AudioRecorder.kt     mic capture → 16 kHz mono WAV
  Settings.kt          API key + vocabulary persistence (~/.config/mutterboard)
```

Config and staged recordings live under `~/.config/mutterboard/`.
