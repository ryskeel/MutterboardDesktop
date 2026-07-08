package com.mutterboard

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File
import java.io.RandomAccessFile

/**
 * Parakeet kept warm in-process via the sherpa-onnx JNI bindings, so a
 * dictation pays only the decode (~0.05x real time) instead of ~1.4s of model
 * load per subprocess run. Loaded lazily when a recording starts in offline
 * mode (overlapping the user speaking) and released when the engine switches
 * back to Default, freeing ~600 MB.
 *
 * If the JNI path breaks for any reason it marks itself failed and
 * [ParakeetTranscriber] falls back to the subprocess route.
 */
object WarmParakeet {
    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var failed = false

    // The JNI build segfaults in FeatureExtractorConfig::ToString (a
    // libstdc++ ABI clash — SIGSEGV, not catchable from Java) on every JVM
    // tried except the Homebrew one this was developed against. Off unless
    // explicitly opted in; dictation then uses the subprocess route, which
    // runs against sherpa's own bundled libs and costs ~1.4s of model load.
    private val disabled = System.getenv("MUTTERBOARD_WARM_JNI") != "1"

    /** Load the model if needed. Blocks (~1.3s); call from a worker thread. */
    @Synchronized
    fun ensureLoaded() {
        if (recognizer != null || failed || disabled || !OfflinePaths.transcriberReady()) return
        runCatching {
            val m = OfflinePaths.parakeetDir
            val config = OfflineRecognizerConfig.Builder()
                .setFeatureConfig(
                    FeatureConfig.Builder().setSampleRate(16000).setFeatureDim(80).build(),
                )
                .setOfflineModelConfig(
                    OfflineModelConfig.Builder()
                        .setTransducer(
                            OfflineTransducerModelConfig.Builder()
                                .setEncoder(File(m, "encoder.int8.onnx").absolutePath)
                                .setDecoder(File(m, "decoder.int8.onnx").absolutePath)
                                .setJoiner(File(m, "joiner.int8.onnx").absolutePath)
                                .build(),
                        )
                        .setTokens(File(m, "tokens.txt").absolutePath)
                        .setModelType("nemo_transducer")
                        // 16-core machine; 8 threads roughly halves long-audio
                        // decode vs 4 with no visible cost elsewhere.
                        .setNumThreads(8)
                        .build(),
                )
                .setDecodingMethod("greedy_search")
                .build()
            recognizer = OfflineRecognizer(config)
            println("[parakeet-warm] model loaded")
        }.onFailure {
            failed = true
            println("[parakeet-warm] JNI load failed, using subprocess: $it")
        }
    }

    /** Decode [wav]; null means unavailable/failed (caller falls back). */
    fun transcribe(wav: File): String? {
        ensureLoaded()
        val rec = recognizer ?: return null
        val samples = readWavMono16k(wav) ?: return null
        // One decode at a time: dictations are serial and the JNI stream API
        // isn't something we need to push concurrency through.
        synchronized(this) {
            return runCatching {
                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(samples, 16000)
                    rec.decode(stream)
                    rec.getResult(stream).text.trim()
                } finally {
                    stream.release()
                }
            }.onFailure { println("[parakeet-warm] decode failed: $it") }.getOrNull()
        }
    }

    @Synchronized
    fun unload() {
        recognizer?.release()
        recognizer = null
    }

    /**
     * Reads the recorder's 16 kHz mono 16-bit WAV as -1..1 floats, walking the
     * RIFF chunks to find "data" rather than assuming a 44-byte header.
     */
    private fun readWavMono16k(wav: File): FloatArray? = runCatching {
        RandomAccessFile(wav, "r").use { f ->
            f.seek(12) // past RIFF////WAVE
            while (true) {
                val id = ByteArray(4).also { f.readFully(it) }.toString(Charsets.US_ASCII)
                val size = Integer.reverseBytes(f.readInt())
                if (id == "data") {
                    val bytes = ByteArray(size)
                    f.readFully(bytes)
                    val out = FloatArray(size / 2)
                    for (i in out.indices) {
                        val s = ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
                        out[i] = s / 32768f
                    }
                    return@use out
                }
                f.seek(f.filePointer + size + (size and 1))
            }
            // Unreachable: the loop exits via return@use or an EOF exception
            // (caught by runCatching) when there is no data chunk.
            @Suppress("UNREACHABLE_CODE")
            return@use null
        }
    }.getOrNull()
}
