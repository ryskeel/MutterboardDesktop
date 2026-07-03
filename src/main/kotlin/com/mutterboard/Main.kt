package com.mutterboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.delay

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
    var vocabulary by mutableStateOf(settings.vocabulary)
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
    fun updateVocabulary(v: String) { vocabulary = v; settings.vocabulary = v }
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
            vocabularyPrompt = vocabulary.ifBlank { null }
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

    // Tray is best-effort: KDE Wayland's SNI bridge often ignores clicks on an
    // AWT tray, so it is not the primary way in. Closing the window quits.
    Tray(
        icon = trayIcon,
        state = trayState,
        tooltip = "Mutterboard — ${controller.status.name.lowercase()}",
        menu = {
            Item(
                if (controller.status == Status.RECORDING) "Stop & transcribe" else "Record",
            ) { controller.toggle() }
            Separator()
            Item("Quit") { exitApplication() }
        },
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = "Mutterboard",
        icon = appIcon,
        state = rememberWindowState(width = 720.dp, height = 900.dp),
    ) {
        // Skiko renders at 1x here — JBR under XWayland ignores both the
        // compositor's 1.7 scale and sun.java2d.uiScale, so the UI came out
        // tiny. Override Compose's density directly, which reliably scales every
        // dp and sp. UI_SCALE is this machine's factor; a distributed build
        // should detect it from the display instead.
        CompositionLocalProvider(LocalDensity provides Density(UI_SCALE, 1f)) {
            MutterboardTheme {
                MainScreen(controller, appIcon)
            }
        }
    }
}

private const val UI_SCALE = 1.7f

@Composable
private fun MainScreen(controller: DictationController, appIcon: Painter) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Header(appIcon)
            SettingsSection(controller)
            RecordSection(controller)
            ResultSection(controller)
        }
    }
}

@Composable
private fun Header(appIcon: Painter) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = appIcon,
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
        )
        Text(
            "Mutterboard",
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

/** A flat, bordered desktop panel — replaces the phone-style elevated card. */
@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f), shape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
    minHeight: Int = 46,
    maxLines: Int = 1,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(9.dp)
    val borderColor by animateColorAsState(
        if (focused) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.background)
            .border(if (focused) 2.dp else 1.dp, borderColor, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .heightIn(min = minHeight.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            maxLines = maxLines,
            interactionSource = interaction,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation =
                if (password) PasswordVisualTransformation() else VisualTransformation.None,
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                inner()
            },
        )
    }
}

/** Records a keyboard combo (Ctrl+Alt+D style) into a persisted setting. */
@Composable
private fun ShortcutField(value: String, onCapture: (String) -> Unit) {
    var capturing by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val shape = RoundedCornerShape(9.dp)
    LaunchedEffect(capturing) { if (capturing) focusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.background)
            .border(
                if (capturing) 2.dp else 1.dp,
                if (capturing) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                shape,
            )
            .focusRequester(focusRequester)
            .onFocusChanged { if (!it.isFocused) capturing = false }
            .onPreviewKeyEvent { e ->
                if (!capturing || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (e.key in MODIFIER_KEYS) return@onPreviewKeyEvent true
                if (e.key == Key.Escape) { capturing = false; return@onPreviewKeyEvent true }
                val parts = buildList {
                    if (e.isCtrlPressed) add("Ctrl")
                    if (e.isAltPressed) add("Alt")
                    if (e.isShiftPressed) add("Shift")
                    if (e.isMetaPressed) add("Super")
                    add(java.awt.event.KeyEvent.getKeyText(e.key.nativeKeyCode))
                }
                onCapture(parts.joinToString("+"))
                capturing = false
                true
            }
            .focusable()
            .clickable { capturing = true }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            if (capturing) "Press a key combo…  (Esc to cancel)" else value,
            style = MaterialTheme.typography.bodyLarge,
            color = if (capturing) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SettingsSection(controller: DictationController) {
    Panel {
        SectionLabel("SETTINGS")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel("Groq API key")
            DesktopField(
                value = controller.apiKey,
                onValueChange = { controller.updateApiKey(it.trim()) },
                placeholder = "gsk_…",
                singleLine = true,
                password = true,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel("Custom vocabulary")
            DesktopField(
                value = controller.vocabulary,
                onValueChange = { controller.updateVocabulary(it) },
                placeholder = "names, brands, jargon…",
                singleLine = false,
                minHeight = 92,
                maxLines = 4,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel("Dictation shortcut")
            ShortcutField(
                value = controller.shortcut,
                onCapture = { controller.updateShortcut(it) },
            )
            Text(
                controller.hotkeyStatus + "  ·  edits apply on next launch; " +
                    "you can also rebind in KDE System Settings.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun RecordSection(controller: DictationController) {
    val recording = controller.status == Status.RECORDING
    val busy = controller.status == Status.TRANSCRIBING

    Panel {
        LevelMeter(active = recording, levelProvider = controller::currentLevel)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Button(
                onClick = { controller.toggle() },
                enabled = !busy,
                shape = RoundedCornerShape(9.dp),
                contentPadding = PaddingValues(horizontal = 30.dp, vertical = 14.dp),
            ) {
                Text(
                    when {
                        recording -> "Stop & transcribe"
                        busy -> "Transcribing…"
                        else -> "Record"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            controller.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (controller.status == Status.ERROR)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ResultSection(controller: DictationController) {
    if (controller.resultText.isBlank()) return
    Panel {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("LAST RESULT")
            TextButton(
                onClick = {
                    Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(StringSelection(controller.resultText), null)
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) { Text("Copy", style = MaterialTheme.typography.bodyMedium) }
        }
        SelectionContainer {
            Text(
                controller.resultText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * The Mutterboard "equalizer" — a row of bars whose heights follow recent mic
 * levels while recording, scrolling left to right.
 */
@Composable
private fun LevelMeter(active: Boolean, levelProvider: () -> Float) {
    val bars = remember { ArrayDeque<Float>(BAR_COUNT).apply { repeat(BAR_COUNT) { add(0f) } } }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(active) {
        while (active) {
            if (bars.size >= BAR_COUNT) bars.removeFirst()
            bars.addLast(levelProvider())
            tick++
            delay(50)
        }
        if (!active) {
            bars.clear()
            repeat(BAR_COUNT) { bars.add(0f) }
            tick++
        }
    }

    val barColor = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(60.dp)) {
        @Suppress("UNUSED_EXPRESSION") tick // read so recomposition redraws
        val values = bars.toList()
        val n = values.size.coerceAtLeast(1)
        val gap = 3.dp.toPx()
        val barWidth = (size.width - gap * (n - 1)) / n
        val midY = size.height / 2f
        values.forEachIndexed { i, v ->
            val h = (v.coerceIn(0f, 1f) * size.height).coerceAtLeast(2.dp.toPx())
            val x = i * (barWidth + gap)
            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, midY - h / 2f),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

private const val BAR_COUNT = 40

private val MODIFIER_KEYS = setOf(
    Key.CtrlLeft, Key.CtrlRight,
    Key.AltLeft, Key.AltRight,
    Key.ShiftLeft, Key.ShiftRight,
    Key.MetaLeft, Key.MetaRight,
)
