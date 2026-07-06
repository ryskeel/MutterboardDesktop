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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
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

    var apiKey by mutableStateOf(settings.apiKey)
        private set
    var customWords by mutableStateOf(parseWords(settings.vocabulary))
        private set
    var shortcut by mutableStateOf(settings.shortcut)
        private set
    var status by mutableStateOf(Status.IDLE)
        private set
    var resultText by mutableStateOf("")
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var hotkeyStatus by mutableStateOf("Setting up shortcut…")
        private set

    fun reportHotkeyStatus(v: String) { hotkeyStatus = v }

    fun currentLevel(): Float = recorder.level

    fun updateApiKey(v: String) { apiKey = v; settings.apiKey = v }
    fun updateCustomWords(words: List<String>) {
        customWords = words
        // Stored newline-separated like the Android app's custom_words pref.
        settings.vocabulary = words.joinToString("\n")
    }
    fun updateShortcut(v: String) { shortcut = v; settings.shortcut = v }

    fun toggle() {
        when (status) {
            Status.RECORDING -> stopAndProcess()
            Status.TRANSCRIBING -> {} // busy; ignore
            else -> startRecording()
        }
    }

    private fun startRecording() {
        if (apiKey.isBlank()) {
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
            recorder.start()
            status = Status.RECORDING
            message = null
        } catch (e: Exception) {
            status = Status.ERROR
            message = "Could not open the microphone: ${e.message}"
        }
    }

    private fun stopAndProcess() {
        val wav = recorder.stop(settings.cacheDir)
        if (wav == null) {
            status = Status.IDLE
            message = "That was too short — hold on a bit longer."
            return
        }
        status = Status.TRANSCRIBING
        message = null
        val whisper = GroqWhisperClient(apiKey).apply {
            // Bias Whisper toward the user's vocabulary, same as the Android IME.
            vocabularyPrompt = customWords.joinToString(", ").ifBlank { null }
        }
        val refiner = GroqRefiner(apiKey)
        whisper.transcribe(wav) { raw ->
            wav.delete()
            if (raw.isNullOrBlank()) {
                whisper.close(); refiner.close()
                status = Status.ERROR
                message = "Transcription failed — check your API key and connection."
                return@transcribe
            }
            refiner.refine(raw) { refined ->
                whisper.close(); refiner.close()
                deliver(refined ?: raw)
            }
        }
    }

    private fun deliver(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard
            .setContents(StringSelection(text), null)
        resultText = text
        status = Status.DONE
        message = "Copied to clipboard."
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

fun main() = application {
    val controller = remember { DictationController() }
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
    var windowVisible by remember { mutableStateOf(true) }
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
                MainScreen(controller, appIcon) { combo ->
                    controller.updateShortcut(combo)
                    // Apply immediately via kglobalaccel — no restart, no dialog.
                    hotkey.rebind(combo) { controller.reportHotkeyStatus(it) }
                }
            }
        }
    }
}

private const val UI_SCALE = 1.7f

// Brand wordmark font — the same Montserrat Black the Android header uses.
private val BrandFont = FontFamily(Font("montserrat_black.ttf", FontWeight.Black))

@Composable
private fun MainScreen(
    controller: DictationController,
    appIcon: Painter,
    onShortcutCaptured: (String) -> Unit,
) {
    var showKeyDialog by remember { mutableStateOf(false) }
    var showRemoveKey by remember { mutableStateOf(false) }

    // Compose desktop doesn't drop text-field focus when you click elsewhere,
    // so the whole page clears focus on any click no child claims first.
    val focusManager = LocalFocusManager.current
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { focusManager.clearFocus() }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Cards stop growing at a phone-ish width and stay centered when
            // the window is stretched wide.
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
            Header(appIcon)

            Spacer(Modifier.height(32.dp))
            SectionHeader("Transcription")
            Spacer(Modifier.height(12.dp))
            TranscriptionCard(
                apiKey = controller.apiKey,
                onAddKey = { showKeyDialog = true },
                onRequestRemoveKey = { showRemoveKey = true },
            )

            Spacer(Modifier.height(40.dp))
            SectionHeader("Vocabulary")
            Spacer(Modifier.height(12.dp))
            VocabularyCard(
                words = controller.customWords,
                onAdd = { controller.updateCustomWords(controller.customWords + it) },
                onRemove = { word ->
                    controller.updateCustomWords(controller.customWords.filterNot { it == word })
                },
            )

            Spacer(Modifier.height(40.dp))
            SectionHeader("Shortcut")
            Spacer(Modifier.height(12.dp))
            ShortcutCard(controller, onShortcutCaptured)

            if (controller.resultText.isNotBlank()) {
                Spacer(Modifier.height(40.dp))
                SectionHeader("Last result")
                Spacer(Modifier.height(12.dp))
                ResultCard(controller)
            }

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
}

@Composable
private fun Header(appIcon: Painter) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = appIcon,
                contentDescription = null,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(8.dp))
            Text("Mutterboard", fontSize = 30.sp, fontFamily = BrandFont)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Voice dictation",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 48.dp),
        )
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

@Composable
private fun TranscriptionCard(
    apiKey: String,
    onAddKey: () -> Unit,
    onRequestRemoveKey: () -> Unit,
) {
    BrandCard {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "Whisper Large v3 Turbo transcribes, Llama tidies the phrasing, and " +
                    "the text lands in your clipboard — all through your Groq key.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            if (apiKey.isBlank()) {
                Button(onClick = onAddKey) { Text("Add API key") }
            } else {
                SavedKeyRow(onRequestEdit = onAddKey, onRequestRemove = onRequestRemoveKey)
            }
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
                Button(onClick = { addDraft() }, enabled = draft.isNotBlank()) { Text("Add") }
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

@Composable
private fun ResultCard(controller: DictationController) {
    BrandCard {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            SelectionContainer {
                Text(
                    controller.resultText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = {
                        Toolkit.getDefaultToolkit().systemClipboard
                            .setContents(StringSelection(controller.resultText), null)
                    },
                ) { Text("Copy") }
            }
        }
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
    // Same states as ShortcutButton: peach pill with a deeper peach ring at
    // rest, thin primary border when focused.
    val borderColor by animateColorAsState(
        if (focused) MaterialTheme.colorScheme.primary else accentBorderColor,
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(accentContainerColor)
            .border(1.dp, borderColor, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
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

    Box(
        modifier = Modifier
            .clip(shape)
            // Peach pill like the vocabulary tags, with the deeper peach ring;
            // the primary border is the "listening for keys" indicator.
            .background(accentContainerColor)
            .border(
                1.dp,
                if (capturing) MaterialTheme.colorScheme.primary else accentBorderColor,
                shape,
            )
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
