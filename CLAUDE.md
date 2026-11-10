# Mutterboard Desktop

Voice dictation for Linux (KDE/Wayland). Press a global shortcut, talk, press
again — cleaned-up text is pasted where you were typing. Kotlin + Compose
Multiplatform, one package: `src/main/kotlin/com/mutterboard/`. Ships as a
Flatpak; the user runs the Flatpak, not a local build, so app data lives under
`~/.var/app/io.github.ryskeel.Mutterboard/`.

## TODO first: merge `cleanup-toggle` (added 2026-11-09)

**Bring this up with the user at the start of the session.** The
`cleanup-toggle` branch adds a "Clean up text" switch (Qwen off → raw
Parakeet text, llama-server never starts). The user runs a local build of it
on their Steam Deck (still labeled 1.4.3), where the Qwen model can't share
memory with games. Merge it into `main` before cutting the next release, or
the in-app updater will install a build without the switch and take it away.
Once merged and released, delete this section.

Also found then: `scripts/build-flatpak.sh` dies on a machine with no
`~/.jdks` — `ls -d ~/.jdks/jdk-21*` fails under `set -o pipefail` before the
Temurin download runs. `./gradlew` also needs `JAVA_HOME` set on a machine
with no system Java.

## Current state: 1.3.0, in testing (shipped 2026-08-11)

**Do not treat the durability work as finished.** 1.3.0 rewrote the recording
pipeline around one requirement: *a recording must never be lost*. The user is
now running it as their daily driver specifically to prove that out. What
they're validating:

- It works **100% of the time** — the old bug hit roughly 1 in 20 dictations.
- It survives being **left running for very long periods**.
- It **never bugs out and leaves them with nothing** — no silent failures, no
  vanished recordings.

If a session opens with a report of a lost or stuck dictation, that is a
regression against the whole point of this release. Treat it as the priority,
and start from the history on disk (below) — a lost take should now always
leave evidence.

### What changed, and why it matters when debugging

Recordings are the source of truth. `pw-record` writes straight into
`data/mutterboard/recordings/audio/`, and `RecordingStore` indexes the take as
`PENDING` in `history.json` **before** transcription starts. No failure path
deletes a WAV. On startup `reconcile()` turns stranded `PENDING` entries into
retryable `FAILED` ones and adopts orphan WAVs that never got indexed.

The three long-recording bugs that were losing takes (all in `AudioRecorder`
unless noted):

1. pw-record got 2s to finalize the WAV header before SIGKILL; a big file that
   missed it claimed ~0 samples and transcribed as nothing. Now 15s, plus
   `repairHeader()` rewrites the length from the file's real size regardless.
2. The silence check averaged RMS over the whole file, so a long take that's
   mostly pauses scored below a short one and was deleted. Now it's the
   loudest 200ms window, streamed. A take that still reads silent is *kept*.
3. Audio past Groq's ~13-minute upload cap failed outright — `WavSplitter` now
   splits at low-energy boundaries and `GroqWhisperClient` uploads in sequence.

Silent hangs: every pipeline callback goes through `guard()` in
`DictationController` so an exception can't strand the state machine on a
worker thread; both OkHttp clients have `callTimeout`; a 10-minute watchdog
resolves anything still `PENDING`. `liveTakeId` decides which take owns the UI,
so a background re-transcribe or a late watchdog can't repaint over a newer
dictation.

Failures raise a desktop notification (`Notifier`, D-Bus
`org.freedesktop.Notifications`) — the app is driven from the tray, so error
messages in the settings window were going unseen. That was the real reason
failures felt like "nothing happened".

### Debugging a reported loss

```bash
# the index and the audio — a lost take should still be here
cat ~/.var/app/io.github.ryskeel.Mutterboard/data/mutterboard/recordings/history.json
ls -la ~/.var/app/io.github.ryskeel.Mutterboard/data/mutterboard/recordings/audio/
```

An entry with `status: FAILED` carries an `error` string and is re-transcribable
from the History card. An orphan WAV with no entry means the app died mid-take.
Neither should ever be *absent* — if the audio is genuinely gone, that's the
serious case.

Run the app in a terminal (`flatpak run io.github.ryskeel.Mutterboard`) to see
`[audio]`, `[split]`, `[dictation]`, `[timing]`, and `[paste]` logging.

## Working on this project

- **Verify by running it.** Build with `./gradlew installDesktop` (installs to
  `~/.local/opt` + a menu entry) during iteration, and remove that dev copy at
  ship time. Note the dev copy uses `~/.config/mutterboard` and
  `~/.local/share/mutterboard` — *different data from the Flatpak*, and both
  bind the same global shortcut, so only one can usefully run at a time.
- **Design language mirrors the Mutterboard Android app**: peach accent pills,
  white cards with hairline borders, inline disclosure over modals, minimal
  text. Handy is the interaction reference.
- **Audio capture must stay on `pw-record`.** `javax.sound` opens a line in
  this Compose/AWT process but returns digital silence; a bare JVM program
  records fine. Don't "simplify" it back.
- **Never restart the user's system audio to debug**, and don't pin the app to
  a specific input device.

### Shipping

1. Bump `version` and `packageVersion` in `build.gradle.kts`, add a `<release>`
   to `flatpak/*.metainfo.xml`.
2. Commit and push `main`.
3. `./scripts/build-flatpak.sh` → `build/Mutterboard-<version>-x86_64.flatpak`
4. `gh release create v<version> ./build/Mutterboard-<version>-x86_64.flatpak`
5. Install over the user's copy and relaunch; remove the `installDesktop` dev
   copy.
