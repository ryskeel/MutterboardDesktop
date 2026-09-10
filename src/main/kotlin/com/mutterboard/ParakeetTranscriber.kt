package com.mutterboard

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Fully offline transcription: NVIDIA Parakeet TDT 0.6B (int8) run through the
 * sherpa-onnx CLI as a subprocess — the same shell-out pattern as pw-record.
 * The recorder's 16 kHz mono s16 WAV is exactly the model's native input.
 *
 * Each call pays ~1.3 s of model load before the actual decode (which runs at
 * ~0.05x real time), so a dictation lands in roughly two seconds. Keeping the
 * model warm via the JNI bindings is the known upgrade path if that ever feels
 * slow.
 */
class ParakeetTranscriber : Transcriber {

    override fun transcribe(audioFile: File, onResult: (Transcription) -> Unit) {
        Thread {
            val text = runCatching { transcribeSplit(audioFile) }
                .onFailure { log("transcribe threw: ${it.stackTraceToString()}") }
                .getOrNull()
            onResult(
                if (text == null) {
                    Transcription.Failed("the local speech model didn't return a result")
                } else {
                    Transcription.Text(text)
                },
            )
        }.apply { isDaemon = true; name = "parakeet" }.start()
    }

    /**
     * Decodes [wav] in [WavSplitter] pieces when it's past what the model can
     * take in one pass, joining the text in order. Any piece failing fails the
     * whole take — a transcript with a silent hole in the middle is worse than
     * a retryable error, and the audio is untouched either way.
     */
    private fun transcribeSplit(wav: File): String? {
        if (!WavSplitter.needsSplit(wav)) return transcribeOne(wav)
        val workDir = Files.createTempDirectory("mutterboard-split").toFile()
        try {
            val pieces = WavSplitter.split(wav, workDir)
            val texts = pieces.mapIndexed { i, piece ->
                transcribeOne(piece) ?: run {
                    log("piece ${i + 1}/${pieces.size} of ${wav.name} failed")
                    return null
                }
            }
            return texts.filter { it.isNotBlank() }.joinToString(" ")
        } finally {
            workDir.deleteRecursively()
        }
    }

    /** Warm JNI recognizer first (fast path); subprocess as fallback. */
    private fun transcribeOne(wav: File): String? =
        WarmParakeet.transcribe(wav)
            ?: runCatching { run(wav) }
                .onFailure { log("transcribe threw: ${it.stackTraceToString()}") }
                .getOrNull()

    /** Failures land in ~/.local/share/mutterboard/parakeet.log for diagnosis. */
    private fun log(msg: String) {
        println("[parakeet] $msg")
        runCatching {
            File(OfflinePaths.dataDir, "parakeet.log")
                .appendText("${java.time.LocalDateTime.now()} $msg\n")
        }
    }

    private fun run(wav: File): String? {
        val m = OfflinePaths.parakeetDir
        val proc = ProcessBuilder(
            OfflinePaths.sherpaBin.absolutePath,
            "--encoder=${File(m, "encoder.int8.onnx").absolutePath}",
            "--decoder=${File(m, "decoder.int8.onnx").absolutePath}",
            "--joiner=${File(m, "joiner.int8.onnx").absolutePath}",
            "--tokens=${File(m, "tokens.txt").absolutePath}",
            "--model-type=nemo_transducer",
            "--num-threads=8",
            wav.absolutePath,
        ).apply {
            environment()["LD_LIBRARY_PATH"] = OfflinePaths.sherpaLib.absolutePath
            // stderr must stay separate: sherpa logs there while the result
            // JSON goes to stdout, and merging once interleaved the two mid-
            // line, corrupting long results. The log file keeps diagnostics.
            redirectError(File(OfflinePaths.dataDir, "parakeet-stderr.log"))
        }.start()

        val output = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(120, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            log("timed out")
            return null
        }
        // The recognition result is the line of JSON with a "text" field;
        // everything else is progress logging. An empty text is a real result
        // (no speech recognized) — distinct from engine failure (null), so the
        // caller can say "didn't catch that" instead of "engine broken".
        for (line in output.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("{") && t.contains("\"text\"")) {
                val text = runCatching { JSONObject(t).getString("text").trim() }.getOrNull()
                if (text != null) return text
            }
        }
        // The stderr file is overwritten by the next run, and a crash's reason
        // (exit 134, an onnxruntime abort) is only ever in there — keep its tail.
        val stderrTail = runCatching {
            File(OfflinePaths.dataDir, "parakeet-stderr.log").readLines().takeLast(6).joinToString("\n")
        }.getOrDefault("")
        log("no result (exit=${proc.exitValue()}) in output:\n$output\nstderr tail:\n$stderrTail")
        return null
    }
}
