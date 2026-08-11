# Mutterboard

Voice dictation for the Linux desktop. Press a keyboard shortcut, talk, press it
again — and clean, readable text is pasted right where you were typing.

No windows to click through while you work: Mutterboard sits in your system
tray, listens when you ask it to, tidies up what you said (removing the "um"s,
fixing punctuation, keeping your words), and drops the result into whatever app
has focus. It also stays on your clipboard in case you want to paste it again.

## Download

Grab the `.flatpak` file from the
[latest release](https://github.com/ryskeel/MutterboardDesktop/releases/latest),
then:

```bash
flatpak install --user ./Mutterboard-<version>-x86_64.flatpak
```

Launch **Mutterboard** from your app menu. Once installed, the app can update
itself: the About section at the bottom of settings checks GitHub for new
releases and installs them in one click.

**Requirements:** a 64-bit (x86_64) Linux machine with Flatpak and the Flathub
remote (standard on most distros — the needed runtime installs automatically).
Built and tested on Bazzite with KDE Plasma; the global shortcut and tray icon
are KDE-native, so KDE is the smoothest experience today.

## Choose how it transcribes

Two engines, switchable anytime in settings:

- **Default (cloud)** — audio goes to Groq's fast API: Whisper for
  speech-to-text, then a Llama pass that cleans the wording. You bring your own
  [free Groq API key](https://console.groq.com/keys); nothing is bundled and
  the key never leaves your machine except to talk to Groq.
- **Offline** — everything runs on your computer, no internet and no key.
  One in-app download (about 5.5 GB) fetches the Parakeet speech model and a
  Qwen cleanup model; they use your GPU when available and fall back to CPU.
  Your voice never leaves the machine.

## Your recordings are kept

Every dictation is saved to disk — the audio and the transcript — the moment
you stop talking, before anything is sent anywhere. If transcription fails,
the network drops, or the app is killed mid-sentence, the recording is still
there.

The History section at the bottom of settings lists your recent dictations.
Each one can be copied again or re-transcribed from the original audio, and
**Open folder** takes you straight to the WAV files. Recordings are kept for
30 days by default (adjustable to 7, 90, or forever), and **Clear** deletes
them all.

## What else it does

- **Custom vocabulary** — add names and jargon it should get right.
- **Recording cues** — a soft pop when recording starts and stops, with a
  volume slider (or turn it off).
- **Rebindable shortcut** — click the shortcut box, press the combo you want.
- **Launch at login** — starts quietly in the tray, ready for the shortcut.
- **In-app updates** — check and install new versions from settings.

## Good to know

- The first launch on KDE shows a one-time approval for the global shortcut.
- On very high-DPI screens the app auto-detects your display scale; if it
  guesses wrong: `flatpak override --user --env=MUTTERBOARD_SCALE=2 io.github.ryskeel.Mutterboard`
- Settings, models, and saved recordings live under
  `~/.var/app/io.github.ryskeel.Mutterboard/` (recordings in
  `data/mutterboard/recordings/`); uninstalling with
  `flatpak uninstall --delete-data` removes everything.
- Long dictations are fine — recordings past the cloud engine's upload limit
  are split and transcribed in pieces automatically.

## Building from source

Kotlin + Compose Multiplatform, sharing its transcription pipeline with the
Mutterboard Android app.

```bash
./gradlew installDesktop        # local build into ~/.local/opt + app menu entry
./scripts/build-flatpak.sh      # the distributable .flatpak bundle
```

The Flatpak build needs a Temurin JDK 21 in `~/.jdks` (the script checks) and
`org.flatpak.Builder` installed. Source layout is one Kotlin package,
`src/main/kotlin/com/mutterboard/` — `Main.kt` is the window + controller,
with recording, transcription, refinement, hotkey, tray, and updater each in
their own file.

## License

MIT
