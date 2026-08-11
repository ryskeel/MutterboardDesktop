package com.mutterboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

enum class Status { IDLE, RECORDING, TRANSCRIBING, DONE, ERROR }

/**
 * Owns the whole record -> Whisper -> refiner -> clipboard flow and exposes it
 * as observable Compose state. Callbacks arrive on OkHttp threads and write
 * snapshot state directly, which Compose marshals to a recomposition.
 */
class DictationController {
    private val settings = Settings()
    private val recorder = AudioRecorder()
    private val sounds = SoundPlayer(settings.cacheDir)
    private val store = RecordingStore {
        RecordingStore.Retention(settings.historyMaxTakes, settings.historyMaxDays)
    }

    var apiKey by mutableStateOf(settings.apiKey)
        private set
    var offlineMode by mutableStateOf(settings.offlineMode)
        private set
    var offlineReady by mutableStateOf(OfflinePaths.allReady())
        private set
    /** Non-null while the model download runs; 0..1 across all artifacts. */
    var downloadProgress by mutableStateOf<Float?>(null)
        private set
    var downloadMessage by mutableStateOf<String?>(null)
        private set
    var soundEnabled by mutableStateOf(settings.soundEnabled)
        private set
    var soundVolume by mutableStateOf(settings.soundVolume)
        private set
    var launchAtStartup by mutableStateOf(settings.launchAtStartup)
        private set
    var customWords by mutableStateOf(parseWords(settings.vocabulary))
        private set
    var shortcut by mutableStateOf(settings.shortcut)
        private set
    var status by mutableStateOf(Status.IDLE)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    /** Every retained dictation, newest first — the History card's model. */
    var takes by mutableStateOf<List<Take>>(emptyList())
        private set
    /** Ids currently being re-transcribed from the History card. */
    var retrying by mutableStateOf<Set<String>>(emptySet())
        private set
    var historyBytes by mutableStateOf(0L)
        private set
    var historyMaxTakes by mutableStateOf(settings.historyMaxTakes)
        private set
    var historyMaxDays by mutableStateOf(settings.historyMaxDays)
        private set
    var hotkeyStatus by mutableStateOf("Setting up shortcut…")
        private set
    val appVersion = Updater.currentVersion
    var updateState by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    /** Id of the take being recorded, so the WAV is indexed under the right one. */
    private var pendingId: String? = null

    /** The take the user is waiting on — the only one allowed to drive the UI. */
    @Volatile
    private var liveTakeId: String? = null

    init {
        // Publish every history change back into snapshot state; the store is
        // written from OkHttp threads, worker threads, and the UI alike.
        store.onChange = { list ->
            takes = list
            historyBytes = store.bytesOnDisk()
        }
        // Recover anything the last run left mid-flight: takes stuck PENDING
        // become retryable, orphan WAVs get adopted back into the index.
        store.reconcile()

        // If the app comes up in offline mode (autostart, typically), warm the
        // engines now so even the first dictation is fast.
        if (offlineMode && OfflinePaths.allReady()) warmOffline()
        // Quiet startup check so the About card can offer an update without a
        // click; failures stay silent here.
        checkForUpdates(silent = true)
    }

    fun checkForUpdates(silent: Boolean = false) {
        when (updateState) {
            UpdateState.Checking, is UpdateState.Downloading, UpdateState.Installing -> return
            else -> {}
        }
        if (!silent) updateState = UpdateState.Checking
        Updater.checkLatest { release, err ->
            updateState = when {
                err != null -> if (silent) UpdateState.Idle else UpdateState.Failed(err)
                release != null && Updater.isNewer(release.version) -> UpdateState.Available(release)
                else -> if (silent) UpdateState.Idle else UpdateState.UpToDate
            }
        }
    }

    fun installUpdate() {
        val release = (updateState as? UpdateState.Available)?.release ?: return
        updateState = UpdateState.Downloading(0f)
        Updater.downloadAndInstall(
            release = release,
            cacheDir = settings.cacheDir,
            onProgress = { updateState = UpdateState.Downloading(it) },
            onInstalling = { updateState = UpdateState.Installing },
            onDone = { err ->
                updateState =
                    if (err != null) UpdateState.Failed(err)
                    else UpdateState.Installed(release.version)
            },
        )
    }

    fun reportHotkeyStatus(v: String) { hotkeyStatus = v }

    fun currentLevel(): Float = recorder.level

    fun updateApiKey(v: String) { apiKey = v; settings.apiKey = v }
    fun updateCustomWords(words: List<String>) {
        customWords = words
        // Stored newline-separated like the Android app's custom_words pref.
        settings.vocabulary = words.joinToString("\n")
    }
    fun updateShortcut(v: String) { shortcut = v; settings.shortcut = v }
    fun updateSoundEnabled(v: Boolean) { soundEnabled = v; settings.soundEnabled = v }
    fun updateSoundVolume(v: Float) { soundVolume = v }
    fun commitSoundVolume() {
        settings.soundVolume = soundVolume
        // Preview the cue at the new volume so the slider is tunable by ear.
        if (soundEnabled) sounds.playStart(soundVolume)
    }
    fun updateLaunchAtStartup(v: Boolean) {
        launchAtStartup = v
        settings.launchAtStartup = v
        Autostart.sync(v)
    }

    fun updateOfflineMode(v: Boolean) {
        offlineMode = v
        settings.offlineMode = v
        // Warm both local models right away so the first dictation is fast;
        // free their memory when going back to the cloud.
        if (v) warmOffline() else {
            LlamaServer.stop()
            WarmParakeet.unload()
        }
    }

    private fun localRefiner() = GroqRefiner(
        apiKey = "",
        baseUrl = LlamaServer.BASE_URL,
        model = LlamaServer.MODEL,
        localQwen = true,
    )

    /**
     * Bring the offline engines up off the UI thread: Parakeet into this
     * process, llama-server as a child, then one prime request so the big
     * cleanup prompt is prefix-cached before the first real dictation.
     */
    private fun warmOffline() {
        LlamaServer.ensureRunning()
        Thread {
            WarmParakeet.ensureLoaded()
            if (LlamaServer.awaitHealthy(60_000) && LlamaServer.shouldPrime()) {
                localRefiner().prime()
            }
        }.apply { isDaemon = true }.start()
    }

    /** Deletes the offline engine from disk and returns dictation to Default. */
    fun removeOfflineModels() {
        updateOfflineMode(false) // also stops llama-server
        offlineReady = false
        downloadMessage = null
        Thread {
            with(OfflinePaths) {
                listOf(
                    sherpaDir, llamaDir, llamaVulkanDir,
                    java.io.File(dataDir, "models"),
                    java.io.File(dataDir, "downloads"),
                ).forEach { it.deleteRecursively() }
            }
            offlineReady = OfflinePaths.allReady()
        }.apply { isDaemon = true }.start()
    }

    fun downloadOfflineModels() {
        if (downloadProgress != null) return
        downloadProgress = 0f
        downloadMessage = null
        OfflineDownloader(
            onProgress = { p, label ->
                downloadProgress = p
                downloadMessage = label
            },
            onDone = { err ->
                downloadProgress = null
                downloadMessage = err
                offlineReady = OfflinePaths.allReady()
                if (err == null && offlineMode) warmOffline()
            },
        ).start()
    }

    fun toggle() {
        when (status) {
            Status.RECORDING -> stopAndProcess()
            Status.TRANSCRIBING -> {} // busy; ignore
            else -> startRecording()
        }
    }

    private fun startRecording() {
        if (offlineMode) {
            if (!OfflinePaths.allReady()) {
                status = Status.ERROR
                message = "Download the offline models first."
                return
            }
            // Load both local models while the user is still speaking.
            warmOffline()
        } else if (apiKey.isBlank()) {
            status = Status.ERROR
            message = "Add your Groq API key first."
            return
        }
        if (!recorder.isSupported()) {
            status = Status.ERROR
            message = "No microphone input line is available."
            return
        }
        try {
            // Straight into the recordings directory, not a temp file: from
            // here on the audio exists on disk and outlives anything that can
            // go wrong downstream.
            pendingId = store.newId()
            recorder.start(store.newAudioFile(pendingId!!))
            status = Status.RECORDING
            message = null
            if (soundEnabled) sounds.playStart(soundVolume)
        } catch (e: Exception) {
            pendingId = null
            status = Status.ERROR
            message = "Could not open the microphone: ${e.message}"
        }
    }

    private fun stopAndProcess() {
        val capture = recorder.stop()
        val id = pendingId ?: store.newId()
        pendingId = null
        // After the mic closes (so the pop can't bleed into the take), and
        // including the too-short case — the ear always hears recording end.
        if (soundEnabled) sounds.playStop(soundVolume)

        val (wav, durationMs) = when (capture) {
            is AudioRecorder.Capture.TooShort -> {
                status = Status.IDLE
                message = "That was too short — hold on a bit longer."
                return
            }
            // Kept, indexed, and offered for retry: a take that reads as silent
            // is far more likely a wrong input device than a wasted press, and
            // guessing wrong here is exactly how a recording used to vanish.
            is AudioRecorder.Capture.Silent -> {
                store.add(
                    newTake(id, capture.wav, capture.durationMs).copy(
                        status = TakeStatus.FAILED,
                        error = "Nothing above the noise floor — check your microphone.",
                    ),
                )
                status = Status.ERROR
                message = "Didn't hear anything — check your microphone. The recording was kept."
                Notifier.notify(
                    "Mutterboard heard silence",
                    "Check your input device. The recording is saved in your history.",
                )
                return
            }
            is AudioRecorder.Capture.Ok -> capture.wav to capture.durationMs
        }

        // Indexed as PENDING *before* transcription starts, so a crash, a hang,
        // or a hard kill still leaves a recoverable entry pointing at real audio.
        store.add(newTake(id, wav, durationMs))
        liveTakeId = id
        status = Status.TRANSCRIBING
        message = null
        transcribe(id, wav)
    }

    private fun newTake(id: String, wav: java.io.File, durationMs: Long) = Take(
        id = id,
        at = System.currentTimeMillis(),
        durationMs = durationMs,
        audio = wav.name,
        status = TakeStatus.PENDING,
        engine = if (offlineMode) "offline" else "cloud",
    )

    /**
     * Run a take through transcribe -> refine, recording every outcome against
     * its history entry.
     *
     * Both engines and both entry points land here — a live dictation and a
     * re-transcribe from the History card differ only in whether the take owns
     * the UI ([liveTakeId]). The audio is never deleted on any path through
     * this function; that is the whole point of it.
     */
    private fun transcribe(id: String, wav: java.io.File) {
        armWatchdog(id)
        val offline = offlineMode
        val transcriber: Transcriber = if (offline) {
            LlamaServer.ensureRunning()
            ParakeetTranscriber()
        } else {
            GroqWhisperClient(apiKey).apply {
                // Bias Whisper toward the user's vocabulary, same as the Android IME.
                vocabularyPrompt = customWords.joinToString(", ").ifBlank { null }
            }
        }
        val t0 = System.currentTimeMillis()

        transcriber.transcribe(wav, guard(id) { result ->
            val tTranscribed = System.currentTimeMillis()
            when (result) {
                is Transcription.Failed -> {
                    transcriber.close()
                    fail(id, result.reason)
                }
                is Transcription.Text -> {
                    val raw = result.value
                    // Stored before refinement, so even a crash during cleanup
                    // leaves the user's words on disk.
                    store.update(id) { it.copy(raw = raw) }
                    if (raw.isBlank()) {
                        transcriber.close()
                        fail(id, "no speech was recognized in the recording")
                        return@guard
                    }
                    // Refinement is best-effort by design: any failure falls
                    // back to the raw transcript rather than losing the take.
                    if (offline && !LlamaServer.awaitHealthy()) {
                        println("[timing] parakeet=${tTranscribed - t0}ms refine=skipped (server down)")
                        transcriber.close()
                        succeed(id, raw, raw)
                        return@guard
                    }
                    val refiner = if (offline) localRefiner() else GroqRefiner(apiKey)
                    refiner.refine(raw, guard(id) { refined ->
                        refiner.close()
                        transcriber.close()
                        println(
                            "[timing] transcribe=${tTranscribed - t0}ms " +
                                "refine=${System.currentTimeMillis() - tTranscribed}ms",
                        )
                        succeed(id, raw, refined ?: raw)
                    })
                }
            }
        })
    }

    /**
     * Wraps a pipeline callback so it can't escape without resolving the take.
     * An exception on an OkHttp or worker thread used to strand the dictation
     * in TRANSCRIBING forever with the transcript lost; now it lands as a
     * failed, retryable take with the audio still on disk.
     */
    private fun <T> guard(id: String, body: (T) -> Unit): (T) -> Unit = { value ->
        try {
            body(value)
        } catch (e: Throwable) {
            println("[dictation] $id threw: ${e.stackTraceToString()}")
            fail(id, "something went wrong handling the result")
        }
    }

    /** Commit a finished take, and deliver it if it's the one the user is waiting on. */
    private fun succeed(id: String, raw: String, text: String) {
        // Trailing space after sentence-ending punctuation, same as the Android
        // IME, so the next dictation doesn't run into this one.
        val finished = text.trimEnd().let {
            if (it.endsWith('.') || it.endsWith('!') || it.endsWith('?')) "$it " else it
        }
        store.update(id) {
            it.copy(status = TakeStatus.DONE, raw = raw, text = finished, error = "")
        }
        retrying = retrying - id
        if (claimLive(id)) deliver(finished)
    }

    /**
     * Record a failure against the take. The audio stays on disk and the entry
     * stays in the History card with a Re-transcribe button, so "it failed" is
     * never the same as "it's gone".
     */
    private fun fail(id: String, reason: String) {
        println("[dictation] $id failed: $reason")
        store.update(id) { it.copy(status = TakeStatus.FAILED, error = reason) }
        retrying = retrying - id
        if (!claimLive(id)) return
        status = Status.ERROR
        message = "Transcription failed: $reason. The recording is saved in your history."
        Notifier.notify(
            "Transcription failed",
            "$reason.\nThe recording was saved — retry it from Mutterboard's history.",
        )
    }

    /**
     * True if [id] is the take the user is currently waiting on, releasing the
     * claim as it answers. A background re-transcribe, or a watchdog firing
     * late on an abandoned take, must not repaint the UI for a newer dictation.
     */
    private fun claimLive(id: String): Boolean = synchronized(this) {
        if (liveTakeId != id) return false
        liveTakeId = null
        true
    }

    /**
     * Backstop for a pipeline that stops responding entirely. If a take is
     * still unresolved when this fires, it's marked failed so the user gets
     * told — and gets the retry button — instead of watching a spinner forever.
     */
    private fun armWatchdog(id: String) {
        Thread {
            Thread.sleep(WATCHDOG_MS)
            if (store.get(id)?.status != TakeStatus.PENDING) return@Thread
            fail(id, "the engine stopped responding")
        }.apply { isDaemon = true; name = "dictation-watchdog" }.start()
    }

    /** Re-run transcription for a stored take, from the History card. */
    fun retranscribe(id: String) {
        if (id in retrying) return
        val take = store.get(id) ?: return
        val wav = take.audioFile(store) ?: run {
            store.update(id) { it.copy(error = "The audio for this take is gone.") }
            return
        }
        if (!offlineMode && apiKey.isBlank()) {
            store.update(id) { it.copy(error = "Add your Groq API key first.") }
            return
        }
        if (offlineMode && !OfflinePaths.allReady()) {
            store.update(id) { it.copy(error = "Download the offline models first.") }
            return
        }
        retrying = retrying + id
        store.update(id) { it.copy(status = TakeStatus.PENDING, error = "") }
        transcribe(id, wav)
    }

    fun copyTake(take: Take) {
        val text = take.text.ifBlank { take.raw }
        if (text.isNotBlank()) {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }
    }

    fun deleteTake(id: String) = store.delete(id)

    fun clearHistory() = store.clear()

    /** Open the recordings folder in the file manager (portal-routed under Flatpak). */
    fun openRecordingsFolder() {
        store.audioDir.mkdirs()
        runCatching {
            ProcessBuilder("xdg-open", store.audioDir.absolutePath).start()
        }.onFailure { println("[history] could not open folder: ${it.message}") }
    }

    fun updateHistoryMaxTakes(v: Int) {
        historyMaxTakes = v
        settings.historyMaxTakes = v
        // Apply the tighter window now rather than at the next take.
        store.applyRetention()
    }

    fun updateHistoryMaxDays(v: Int) {
        historyMaxDays = v
        settings.historyMaxDays = v
        store.applyRetention()
    }

    /**
     * Put a finished dictation where the user expects it. Called only after the
     * text is already committed to the history on disk, so a clipboard that
     * gets overwritten or a paste that lands in the wrong window is an
     * annoyance rather than a loss.
     */
    private fun deliver(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard
            .setContents(StringSelection(text), null)
        status = Status.DONE
        message = null
        autoPaste()
    }

    /**
     * Synthesize Ctrl+V into whatever app currently has focus, so the text is
     * pasted as one block (not typed key-by-key). Uses java.awt.Robot, which
     * injects through XWayland. Runs off the caller's thread with a small delay
     * so clipboard ownership has settled first.
     */
    private fun autoPaste() {
        Thread {
            try {
                Thread.sleep(150)
                val r = java.awt.Robot()
                r.keyPress(java.awt.event.KeyEvent.VK_CONTROL)
                r.keyPress(java.awt.event.KeyEvent.VK_V)
                r.keyRelease(java.awt.event.KeyEvent.VK_V)
                r.keyRelease(java.awt.event.KeyEvent.VK_CONTROL)
                println("[paste] sent Ctrl+V")
            } catch (e: Exception) {
                println("[paste] failed: ${e.message}")
            }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        /**
         * How long a live dictation may sit unresolved before the watchdog
         * calls it. Comfortably past the worst honest case — a half-hour
         * recording uploading as several segments — so it only ever fires on
         * a pipeline that has genuinely stopped.
         */
        private const val WATCHDOG_MS = 10 * 60 * 1000L

        /**
         * Parses the stored vocabulary into a clean, de-duplicated word list.
         * Also splits on commas so blobs saved by the old free-text field
         * migrate cleanly.
         */
        fun parseWords(raw: String): List<String> =
            raw.split('\n', ',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
    }
}

fun main(args: Array<String>) {
    // Filled in once the UI is up; a second launch's "show" knock lands here.
    val showFromOutside = java.util.concurrent.atomic.AtomicReference<() -> Unit>()
    if (!SingleInstance.acquire(onShowRequest = { showFromOutside.get()?.invoke() })) {
        println("[instance] Mutterboard is already running — told it to open its window")
        return
    }
    runApp(args, showFromOutside)
}

private fun runApp(
    args: Array<String>,
    showFromOutside: java.util.concurrent.atomic.AtomicReference<() -> Unit>,
) = application {
    // --hidden is what the autostart entry passes: tray + shortcut come up,
    // but the settings window stays closed until asked for.
    val startHidden = remember { args.contains("--hidden") }
    val controller = remember { DictationController() }
    // Keep the login entry in step with the setting (also repairs the Exec
    // path after the app is moved or reinstalled).
    LaunchedEffect(Unit) { Autostart.sync(controller.launchAtStartup) }
    val hotkey = remember {
        GlobalHotkey(onActivated = {
            javax.swing.SwingUtilities.invokeLater { controller.toggle() }
        })
    }
    // Register the global shortcut once at startup. KDE shows an approval dialog
    // the first time; editing the combo in-app takes effect on the next launch.
    LaunchedEffect(Unit) {
        hotkey.register(controller.shortcut) { controller.reportHotkeyStatus(it) }
    }
    val trayState = rememberTrayState()
    val appIcon = painterResource("mutterboard-icon.png")
    val trayIcon = painterResource("tray-icon.png")

    // Native tray via the StatusNotifierItem D-Bus protocol. The AWT tray
    // reaches Plasma through XEmbed, which paints the transparent background
    // as a white box and drops clicks; SNI fixes both. AWT stays as the
    // fallback for desktops without a StatusNotifierWatcher.
    var sniFailed by remember { mutableStateOf(false) }
    // Closing the window only hides it: the app keeps running (hotkey and tray
    // stay live) and quits from the tray menu. "Settings" un-hides the window.
    var windowVisible by remember { mutableStateOf(!startHidden) }
    val windowRef = remember { java.util.concurrent.atomic.AtomicReference<javax.swing.JFrame?>(null) }
    val showSettings = {
        javax.swing.SwingUtilities.invokeLater {
            windowVisible = true
            windowRef.get()?.apply {
                state = java.awt.Frame.NORMAL
                toFront()
                requestFocus()
            }
        }
    }
    // Re-launching the app (icon click, `mutterboard` in a terminal) lands in
    // the single-instance socket and surfaces this window instead.
    SideEffect { showFromOutside.set(showSettings) }
    val sniTray = remember {
        SniTray(
            onToggle = { javax.swing.SwingUtilities.invokeLater { controller.toggle() } },
            onSettings = { showSettings() },
            onQuit = { javax.swing.SwingUtilities.invokeLater { exitApplication() } },
        )
    }
    LaunchedEffect(Unit) {
        sniTray.start { ok -> if (!ok) sniFailed = true }
    }
    LaunchedEffect(controller.status) {
        sniTray.update(
            recording = controller.status == Status.RECORDING,
            transcribing = controller.status == Status.TRANSCRIBING,
            statusText = controller.status.name.lowercase(),
        )
    }
    DisposableEffect(Unit) {
        onDispose { sniTray.close() }
    }

    if (sniFailed) {
        Tray(
            icon = trayIcon,
            state = trayState,
            tooltip = "Mutterboard — ${controller.status.name.lowercase()}",
            menu = {
                Item(
                    if (controller.status == Status.RECORDING) "Stop & transcribe" else "Record",
                ) { controller.toggle() }
                Item("Settings") { showSettings() }
                Separator()
                Item("Quit") { exitApplication() }
            },
        )
    }

    Window(
        onCloseRequest = { windowVisible = false },
        visible = windowVisible,
        title = "Mutterboard",
        icon = appIcon,
        // The content below renders at UI_SCALE density (see the density override
        // in the content lambda), but WindowState sizes are measured at the JVM's
        // 1x density under XWayland. So the window must be scaled by the same
        // factor, otherwise it's ~UI_SCALE too small and the lower sections get
        // clipped/scrolled off. 440x820 is the design size in logical dp —
        // deliberately phone-shaped, matching the Android settings screen.
        state = rememberWindowState(
            width = (440 * UI_SCALE).dp,
            height = (820 * UI_SCALE).dp,
        ),
    ) {
        SideEffect { windowRef.set(window) }
        // Skiko renders at 1x here — JBR under XWayland ignores both the
        // compositor's 1.7 scale and sun.java2d.uiScale, so the UI came out
        // tiny. Override Compose's density directly, which reliably scales every
        // dp and sp. UI_SCALE is this machine's factor; a distributed build
        // should detect it from the display instead.
        CompositionLocalProvider(LocalDensity provides Density(UI_SCALE, 1f)) {
            MutterboardTheme {
                MainScreen(
                    controller = controller,
                    onShortcutCaptured = { combo ->
                        controller.updateShortcut(combo)
                        // Apply immediately via kglobalaccel — no restart, no dialog.
                        hotkey.rebind(combo) { controller.reportHotkeyStatus(it) }
                    },
                    onRestart = {
                        Updater.relaunch()
                        exitApplication()
                    },
                )
            }
        }
    }
}

// Display scale for the density override below, best source first:
// MUTTERBOARD_SCALE (explicit override), KWin's output config (KDE — the
// Flatpak mounts it read-only since nothing inside the sandbox exposes the
// compositor scale), Xft DPI via XSETTINGS (GNOME; AWT surfaces it as the
// gnome.Xft/DPI desktop property in 1/1024 dpi), then AWT's screenResolution.
// Lazy so it can't initialize AWT before main() has set sun.java2d.uiScale.
private val UI_SCALE: Float by lazy {
    val env = System.getenv("MUTTERBOARD_SCALE")?.toFloatOrNull()
    val toolkit = java.awt.Toolkit.getDefaultToolkit()
    val xftDpi = runCatching {
        (toolkit.getDesktopProperty("gnome.Xft/DPI") as? Int)?.takeIf { it > 0 }
            ?.let { it / 1024f }
    }.getOrNull()
    val awtDpi = runCatching { toolkit.screenResolution }.getOrDefault(96)
    val scale = (env ?: kwinScale() ?: ((xftDpi ?: awtDpi.toFloat()) / 96f)).coerceIn(1f, 3f)
    println("[ui] scale=$scale (kwin=${kwinScale()}, xftDpi=$xftDpi, awtDpi=$awtDpi, env=${env ?: "unset"})")
    scale
}

/**
 * The compositor scale from KWin's output config, taking the largest across
 * outputs. Checked at the host path first (how the Flatpak sees the RO mount)
 * and then under $XDG_CONFIG_HOME for plain installs.
 */
private fun kwinScale(): Float? = runCatching {
    val home = System.getProperty("user.home")
    val candidates = listOfNotNull(
        java.io.File("$home/.config/kwinoutputconfig.json"),
        System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
            ?.let { java.io.File(it, "kwinoutputconfig.json") },
    )
    val file = candidates.firstOrNull { it.canRead() } ?: return null
    val scales = mutableListOf<Float>()
    fun walk(v: Any?) {
        when (v) {
            is org.json.JSONObject -> {
                v.optDouble("scale").takeIf { !it.isNaN() }?.let { scales.add(it.toFloat()) }
                v.keys().forEach { walk(v.opt(it)) }
            }
            is org.json.JSONArray -> (0 until v.length()).forEach { walk(v.opt(it)) }
        }
    }
    walk(org.json.JSONArray(file.readText()))
    scales.maxOrNull()
}.getOrNull()

// Brand wordmark font — the same Montserrat Black the Android header uses.
private val BrandFont = FontFamily(Font("montserrat_black.ttf", FontWeight.Black))

@Composable
private fun MainScreen(
    controller: DictationController,
    onShortcutCaptured: (String) -> Unit,
    onRestart: () -> Unit,
) {
    var showKeyDialog by remember { mutableStateOf(false) }
    var showRemoveKey by remember { mutableStateOf(false) }
    var showRemoveModels by remember { mutableStateOf(false) }
    var showClearHistory by remember { mutableStateOf(false) }

    // Compose desktop doesn't drop text-field focus when you click elsewhere,
    // so the whole page clears focus on any click no child claims first.
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    Surface(color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { focusManager.clearFocus() }
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Cards stop growing at a phone-ish width and stay centered when
            // the window is stretched wide.
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
            Header()

            Spacer(Modifier.height(32.dp))
            SectionHeader("Transcription")
            Spacer(Modifier.height(12.dp))
            TranscriptionCard(
                controller = controller,
                onAddKey = { showKeyDialog = true },
                onRequestRemoveKey = { showRemoveKey = true },
                onRequestRemoveModels = { showRemoveModels = true },
            )

            Spacer(Modifier.height(24.dp))
            SectionHeader("Vocabulary")
            Spacer(Modifier.height(12.dp))
            VocabularyCard(
                words = controller.customWords,
                onAdd = { controller.updateCustomWords(controller.customWords + it) },
                onRemove = { word ->
                    controller.updateCustomWords(controller.customWords.filterNot { it == word })
                },
            )

            Spacer(Modifier.height(24.dp))
            SectionHeader("Shortcut")
            Spacer(Modifier.height(12.dp))
            ShortcutCard(controller, onShortcutCaptured)

            Spacer(Modifier.height(24.dp))
            SectionHeader("Sounds")
            Spacer(Modifier.height(12.dp))
            SoundsCard(controller)

            Spacer(Modifier.height(24.dp))
            SectionHeader("Startup")
            Spacer(Modifier.height(12.dp))
            StartupCard(controller)

            Spacer(Modifier.height(24.dp))
            SectionHeader("History")
            Spacer(Modifier.height(12.dp))
            HistoryCard(controller, onRequestClear = { showClearHistory = true })

            Spacer(Modifier.height(24.dp))
            SectionHeader("About")
            Spacer(Modifier.height(12.dp))
            AboutCard(controller, onRestart)

            controller.message?.let { msg ->
                Spacer(Modifier.height(24.dp))
                Text(
                    msg,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (controller.status == Status.ERROR) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                .padding(horizontal = 3.dp, vertical = 4.dp),
            style = LocalScrollbarStyle.current.copy(
                unhoverColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                hoverColor = MaterialTheme.colorScheme.outline,
            ),
        )
        }
    }

    if (showKeyDialog) {
        ApiKeyDialog(
            initial = controller.apiKey,
            onDismiss = { showKeyDialog = false },
            onSave = { controller.updateApiKey(it.trim()); showKeyDialog = false },
        )
    }
    if (showRemoveKey) {
        RemoveKeyDialog(
            onDismiss = { showRemoveKey = false },
            onConfirm = { controller.updateApiKey(""); showRemoveKey = false },
        )
    }
    if (showRemoveModels) {
        RemoveModelsDialog(
            onDismiss = { showRemoveModels = false },
            onConfirm = { controller.removeOfflineModels(); showRemoveModels = false },
        )
    }
    if (showClearHistory) {
        ClearHistoryDialog(
            count = controller.takes.size,
            onDismiss = { showClearHistory = false },
            onConfirm = { controller.clearHistory(); showClearHistory = false },
        )
    }
}

@Composable
private fun Header() {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The glyph's true aspect from the icon: 84 wide x 104 tall.
            WaveformMark(Modifier.size(width = 31.dp, height = 38.dp))
            Spacer(Modifier.width(8.dp))
            Text("Mutterboard", fontSize = 30.sp, fontFamily = BrandFont)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Voice dictation",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 39.dp),
        )
    }
}

/**
 * The app icon's waveform, identically proportioned but without the white
 * tile. Geometry and colors are measured from mutterboard-icon.png (192px
 * master): 12px bars with 6px gaps, heights 40/74/104, per-bar colors.
 */
@Composable
private fun WaveformMark(modifier: Modifier) {
    val colors = remember {
        listOf(
            Color(0xFFF9A69A), Color(0xFFF67E6E), Color(0xFFF4543F),
            Color(0xFFF67E6E), Color(0xFFF9A69A),
        )
    }
    val heights = remember { floatArrayOf(40f, 74f, 104f, 74f, 40f) }
    androidx.compose.foundation.Canvas(modifier) {
        // 5 bars + 4 half-bar gaps = 7 bar-widths across; heights scale
        // against the 104px center bar.
        val bar = size.width / 7f
        val gap = bar / 2f
        for (i in heights.indices) {
            val h = size.height * (heights[i] / 104f)
            drawRoundRect(
                color = colors[i],
                topLeft = androidx.compose.ui.geometry.Offset(i * (bar + gap), (size.height - h) / 2f),
                size = androidx.compose.ui.geometry.Size(bar, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(bar / 2f),
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** White card with a hairline border — the Android app's section card. */
@Composable
private fun BrandCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        content = content,
    )
}

/**
 * Engine picker, mirroring the Android app: two radio options in the one
 * Transcription card. "Default" is the cloud pipeline (Whisper + Llama via the
 * user's Groq key); "Offline" runs Parakeet + Qwen locally. Each option
 * reveals its own setup inline when selected — key management for Default,
 * model download/remove for Offline.
 */
@Composable
private fun TranscriptionCard(
    controller: DictationController,
    onAddKey: () -> Unit,
    onRequestRemoveKey: () -> Unit,
    onRequestRemoveModels: () -> Unit,
) {
    BrandCard {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            EngineOption(
                title = "Default",
                subtitle = "Whisper + Llama in the cloud, through your Groq key.",
                selected = !controller.offlineMode,
                onSelect = { controller.updateOfflineMode(false) },
            ) {
                if (controller.apiKey.isBlank()) {
                    FieldButton(text = "Add API key", enabled = true, onClick = onAddKey)
                } else {
                    SavedKeyRow(onRequestEdit = onAddKey, onRequestRemove = onRequestRemoveKey)
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = 48.dp, top = 2.dp, bottom = 2.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            EngineOption(
                title = "Offline",
                subtitle = "Parakeet + Qwen on this computer. No internet or key.",
                selected = controller.offlineMode,
                onSelect = { controller.updateOfflineMode(true) },
            ) {
                val progress = controller.downloadProgress
                when {
                    progress != null -> {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            controller.downloadMessage ?: "Downloading…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    controller.offlineReady -> {
                        ModelsPill(onRequestRemove = onRequestRemoveModels)
                    }
                    else -> {
                        FieldButton(
                            text = "Download models (5.5 GB)",
                            enabled = true,
                            onClick = { controller.downloadOfflineModels() },
                        )
                        controller.downloadMessage?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One selectable engine: a radio row, with the option's setup content shown
 * inline (indented under the label) only while selected.
 */
@Composable
private fun EngineOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onSelect() },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 6.dp),
        ) {
            BrandRadio(selected = selected, onClick = onSelect)
            Column {
                Text(title, fontWeight = FontWeight.Medium)
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Column(Modifier.padding(start = 48.dp, top = 6.dp, bottom = 8.dp), content = content)
        }
    }
}

/** Peach confirmation pill for the downloaded models, matching SavedKeyRow. */
@Composable
private fun ModelsPill(onRequestRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(accentContainerColor)
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            tint = onAccentContainerColor,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text("Models downloaded", fontSize = 12.sp, color = onAccentContainerColor)
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onRequestRemove, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = "Remove models",
                tint = onAccentContainerColor,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Inline saved-key chip, ported from the Android app: a peach pill confirming
 * the key is saved, with edit and delete icons. The raw key is never shown.
 */
@Composable
private fun SavedKeyRow(onRequestEdit: () -> Unit, onRequestRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(accentContainerColor)
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            tint = onAccentContainerColor,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "API key saved",
            fontSize = 12.sp,
            color = onAccentContainerColor,
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onRequestEdit, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = "Edit key",
                tint = onAccentContainerColor,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onRequestRemove, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = "Remove key",
                tint = onAccentContainerColor,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ApiKeyDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    var showKey by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Groq API key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        DesktopField(
                            value = draft,
                            onValueChange = { draft = it },
                            placeholder = "gsk_…",
                            singleLine = true,
                            password = !showKey,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(if (showKey) "Hide" else "Show")
                    }
                }
                TextButton(
                    onClick = { openUrl("https://console.groq.com/keys") },
                    contentPadding = PaddingValues(0.dp),
                ) { Text("Need a key? Get one from Groq") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }, enabled = draft.isNotBlank()) { Text("Done") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun RemoveKeyDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove the API key?") },
        text = {
            Text(
                "This deletes your saved Groq key from this computer. You'll need to " +
                    "add a key again to use dictation.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Remove", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ClearHistoryDialog(count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear your history?") },
        text = {
            Text(
                "This permanently deletes $count " +
                    (if (count == 1) "recording" else "recordings") +
                    " and their transcripts from this computer. It can't be undone.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Clear", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun RemoveModelsDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove the offline models?") },
        text = {
            Text(
                "This frees about 5.5 GB and switches dictation back to Default. " +
                    "You can download the models again anytime.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Remove", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Custom vocabulary, edited inline (the Handy pattern): an add field with an
 * Add button, and the saved words as removable tag badges below. Words are
 * persisted immediately on add/remove.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VocabularyCard(
    words: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    fun addDraft() {
        val cleaned = draft.trim()
        if (cleaned.isEmpty()) return
        // Case-insensitive de-dupe; newest shows first (list stored oldest-first).
        if (words.none { it.equals(cleaned, ignoreCase = true) }) onAdd(cleaned)
        draft = ""
    }
    BrandCard {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "Add words that Mutterboard should remember.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f).onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) {
                            addDraft(); true
                        } else false
                    },
                ) {
                    DesktopField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = "Add a word",
                        singleLine = true,
                    )
                }
                Spacer(Modifier.width(8.dp))
                FieldButton(
                    text = "Add",
                    enabled = draft.isNotBlank(),
                    onClick = { addDraft() },
                )
            }
            if (words.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    words.asReversed().forEach { word ->
                        WordBadge(word = word, onRemove = { onRemove(word) })
                    }
                }
            }
        }
    }
}

/** A single custom word as a peach badge with a × to remove it. */
@Composable
private fun WordBadge(word: String, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(accentContainerColor)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Text(word, fontSize = 13.sp, color = onAccentContainerColor)
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Remove $word",
                tint = onAccentContainerColor,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun ShortcutCard(controller: DictationController, onCapture: (String) -> Unit) {
    BrandCard {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Keyboard shortcut",
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                ShortcutButton(value = controller.shortcut, onCapture = onCapture)
            }
            // The button already shows the combo; only surface the status when
            // something needs attention (approval prompt, bind errors).
            if (!controller.hotkeyStatus.startsWith("Shortcut active")) {
                Spacer(Modifier.height(10.dp))
                Text(
                    controller.hotkeyStatus,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
        }
    }
}

/**
 * Start/stop cue toggle with an inline volume slider. The volume persists on
 * slider release, which also previews the cue at the new level.
 */
@Composable
private fun SoundsCard(controller: DictationController) {
    BrandCard {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Recording sounds", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "A pop when recording starts and stops.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                BrandSwitch(
                    checked = controller.soundEnabled,
                    onCheckedChange = { controller.updateSoundEnabled(it) },
                )
            }
            if (controller.soundEnabled) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Volume",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(14.dp))
                    BrandSlider(
                        value = controller.soundVolume,
                        onValueChange = { controller.updateSoundVolume(it) },
                        onValueChangeFinished = { controller.commitSoundVolume() },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Launch-at-login toggle; the app starts in the tray with the window hidden. */
@Composable
private fun StartupCard(controller: DictationController) {
    BrandCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Launch at login", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Starts in the tray without opening this window.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            BrandSwitch(
                checked = controller.launchAtStartup,
                onCheckedChange = { controller.updateLaunchAtStartup(it) },
            )
        }
    }
}

/**
 * Version + self-update at the bottom of the page. The whole flow is inline:
 * check, update (with progress), then restart — no dialogs.
 */
@Composable
private fun AboutCard(controller: DictationController, onRestart: () -> Unit) {
    val state = controller.updateState
    BrandCard {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Version ${controller.appVersion}", fontWeight = FontWeight.Medium)
                    val note = when (state) {
                        is UpdateState.Available ->
                            "Version ${state.release.version} is available."
                        is UpdateState.Installed ->
                            "Updated to ${state.version} — restart to finish."
                        is UpdateState.Failed -> state.message
                        UpdateState.UpToDate -> "You're up to date."
                        else -> null
                    }
                    if (note != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            note,
                            fontSize = 13.sp,
                            color = if (state is UpdateState.Failed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                when (state) {
                    UpdateState.Idle, UpdateState.UpToDate ->
                        FieldButton(text = "Check for updates", enabled = true) {
                            controller.checkForUpdates()
                        }
                    is UpdateState.Failed ->
                        FieldButton(text = "Retry", enabled = true) {
                            controller.checkForUpdates()
                        }
                    UpdateState.Checking -> StatusNote("Checking…")
                    is UpdateState.Available ->
                        FieldButton(text = "Update", enabled = true) {
                            controller.installUpdate()
                        }
                    is UpdateState.Downloading -> {}
                    UpdateState.Installing -> StatusNote("Installing…")
                    is UpdateState.Installed ->
                        FieldButton(text = "Restart", enabled = true, onClick = onRestart)
                }
            }
            if (state is UpdateState.Downloading) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun StatusNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Every dictation that's still on disk, newest first — the recovery surface
 * for anything the pipeline couldn't deliver. Shows ten by default because
 * that's the span you'd actually scroll for; the rest is one click away.
 */
@Composable
private fun HistoryCard(controller: DictationController, onRequestClear: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val takes = controller.takes
    val shown = if (expanded) takes else takes.take(HISTORY_PREVIEW)

    BrandCard {
        Column(Modifier.fillMaxWidth()) {
            if (takes.isEmpty()) {
                Text(
                    "Your dictations will be kept here, with the audio, so you can " +
                        "copy or re-transcribe them.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            shown.forEachIndexed { i, take ->
                if (i > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                TakeRow(
                    take = take,
                    busy = take.id in controller.retrying,
                    onCopy = { controller.copyTake(take) },
                    onRetry = { controller.retranscribe(take.id) },
                    onDelete = { controller.deleteTake(take.id) },
                )
            }
            if (takes.size > HISTORY_PREVIEW) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(if (expanded) "Show fewer" else "Show all ${takes.size}")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            HistoryFooter(controller, onRequestClear)
        }
    }
}

/** One dictation: when, how long, what it said, and what you can do with it. */
@Composable
private fun TakeRow(
    take: Take,
    busy: Boolean,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val failed = take.status == TakeStatus.FAILED
    val text = take.text.ifBlank { take.raw }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
        Text(
            "${take.timestamp()} · ${take.duration}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        when {
            busy || take.status == TakeStatus.PENDING ->
                Text(
                    "Transcribing…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            // A failed take still shows whatever text did survive, because a
            // refiner failure with a good raw transcript is worth copying.
            text.isNotBlank() ->
                SelectionContainer {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (failed) 2 else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            else ->
                Text(
                    take.error.ifBlank { "No text — the audio is still here." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
        }
        if (failed && text.isNotBlank() && take.error.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                take.error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (text.isNotBlank()) {
                RowAction(Icons.Rounded.ContentCopy, "Copy text", onCopy)
            }
            if (!busy) {
                RowAction(Icons.Rounded.Refresh, "Re-transcribe", onRetry)
            }
            RowAction(Icons.Rounded.DeleteOutline, "Delete", onDelete)
        }
    }
}

@Composable
private fun RowAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * What's on disk and how long it stays. The retention pills are here rather
 * than buried in a settings screen because the folder is the thing users tidy
 * up by hand, and this is where they'll be looking at its size.
 */
@Composable
private fun HistoryFooter(controller: DictationController, onRequestClear: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Keep recordings for", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${controller.takes.size} saved · ${formatSize(controller.historyBytes)} on disk",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RetentionPills.forEach { (label, days) ->
                RetentionPill(
                    label = label,
                    selected = controller.historyMaxDays == days,
                    onClick = { controller.updateHistoryMaxDays(days) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { controller.openRecordingsFolder() }) { Text("Open folder") }
            if (controller.takes.isNotEmpty()) {
                TextButton(onClick = onRequestClear) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private val RetentionPills = listOf(
    "7 days" to 7,
    "30 days" to 30,
    "90 days" to 90,
    "Forever" to 0,
)

/** Peach selection chip, matching the vocabulary badges. */
@Composable
private fun RetentionPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) accentContainerColor else Color.Transparent)
            .border(1.dp, if (selected) accentBorderColor else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) onAccentContainerColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val HISTORY_PREVIEW = 10

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "${bytes / 1_048_576} MB"
    bytes > 0 -> "${(bytes / 1024).coerceAtLeast(1)} KB"
    else -> "nothing"
}

/**
 * Action button shaped exactly like DesktopField — same corner radius, border
 * inset, vertical padding, and text metrics — so field+button rows line up
 * pixel-for-pixel. (Material's Button can't do this: it enforces a 48dp
 * minimum touch target that outgrows the field.) The solid primary fill is
 * what marks it as the action.
 */
@Composable
private fun FieldButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.35f)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(fill)
            // Transparent border keeps the content box the same height as the
            // bordered field.
            .border(2.dp, Color.Transparent, shape)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/**
 * Compact, modern input: soft filled background, rounded corners, and a border
 * that animates to the accent color on focus (built on BasicTextField, so it
 * doesn't carry the stock Material floating-label look).
 */
@Composable
private fun DesktopField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
    password: Boolean = false,
    maxLines: Int = 1,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(9.dp)
    val outerShape = RoundedCornerShape(12.dp)
    val glowColor by animateColorAsState(
        if (focused) accentBorderColor.copy(alpha = 0.6f) else Color.Transparent,
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(outerShape)
            .background(glowColor)
            .padding(3.dp)
            .clip(shape)
            .background(inputBackgroundColor)
            .border(2.dp, MaterialTheme.colorScheme.outline, shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            maxLines = maxLines,
            interactionSource = interaction,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = onAccentContainerColor,
            ),
            cursorBrush = SolidColor(onAccentContainerColor),
            visualTransformation =
                if (password) PasswordVisualTransformation() else VisualTransformation.None,
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = onAccentContainerColor.copy(alpha = 0.5f),
                    )
                }
                inner()
            },
        )
    }
}

/**
 * Shortcut-capture button, the Handy pattern: click it, hold the keys you
 * want, and the combo commits the moment every key is released (shown live
 * while held). Esc or clicking away cancels.
 *
 * Capturing is simply "the box has focus" — one focus target, requested
 * directly from a tap gesture. (A `clickable` here would add its own internal
 * focus node; focus then hops between the two nodes on click, which read as
 * the capture state flashing on and instantly off.)
 */
@Composable
private fun ShortcutButton(value: String, onCapture: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var capturing by remember { mutableStateOf(false) }
    val held = remember { mutableSetOf<Key>() }
    var combo by remember { mutableStateOf(listOf<String>()) }
    val shape = RoundedCornerShape(9.dp)
    val outerShape = RoundedCornerShape(12.dp)

    Box(
        modifier = Modifier
            .clip(outerShape)
            .background(if (capturing) accentBorderColor.copy(alpha = 0.6f) else Color.Transparent)
            .padding(3.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(shape)
                .background(inputBackgroundColor)
                .border(2.dp, MaterialTheme.colorScheme.outline, shape)
                .focusRequester(focusRequester)
                .onFocusChanged { state ->
                    capturing = state.isFocused
                    if (!state.isFocused) {
                        held.clear()
                        combo = emptyList()
                    }
                }
                .onPreviewKeyEvent { e ->
                    if (!capturing) return@onPreviewKeyEvent false
                    when {
                        e.type == KeyEventType.KeyDown && e.key == Key.Escape -> {
                            focusManager.clearFocus()
                            true
                        }
                        e.type == KeyEventType.KeyDown -> {
                            held.add(e.key)
                            combo = comboOf(held)
                            true
                        }
                        e.type == KeyEventType.KeyUp -> {
                            held.remove(e.key)
                            if (held.isEmpty()) {
                                if (combo.isNotEmpty()) onCapture(combo.joinToString("+"))
                                focusManager.clearFocus()
                            }
                            true
                        }
                        else -> false
                    }
                }
                .focusable()
                .pointerInput(Unit) { detectTapGestures { focusRequester.requestFocus() } }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                when {
                    capturing && combo.isEmpty() -> "Enter shortcut"
                    capturing -> combo.joinToString("+")
                    else -> value
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = onAccentContainerColor,
            )
        }
    }
}

/** The held keys as displayable combo parts: modifiers in a fixed order first. */
private fun comboOf(held: Collection<Key>): List<String> {
    val parts = mutableListOf<String>()
    for (name in listOf("Ctrl", "Alt", "Shift", "Super")) {
        if (held.any { modifierName(it) == name }) parts.add(name)
    }
    held.filter { modifierName(it) == null }.forEach {
        parts.add(java.awt.event.KeyEvent.getKeyText(it.nativeKeyCode))
    }
    return parts
}

private fun modifierName(k: Key): String? = when (k) {
    Key.CtrlLeft, Key.CtrlRight -> "Ctrl"
    Key.AltLeft, Key.AltRight -> "Alt"
    Key.ShiftLeft, Key.ShiftRight -> "Shift"
    Key.MetaLeft, Key.MetaRight -> "Super"
    else -> null
}

/** Opens a URL in the default browser (xdg-open: this is a Linux desktop app). */
private fun openUrl(url: String) {
    runCatching { ProcessBuilder("xdg-open", url).start() }
}
