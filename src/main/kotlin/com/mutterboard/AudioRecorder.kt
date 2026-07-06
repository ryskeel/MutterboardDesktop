package com.mutterboard

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Captures the default microphone to a 16 kHz mono 16-bit WAV — the format
 * Whisper wants — by running `pw-record` (PipeWire's recorder) as a subprocess.
 *
 * We shell out instead of using javax.sound because, inside this Compose/AWT
 * app, the JVM's own capture line opens but returns digital silence (a native
 * audio-engine problem specific to the GUI process — a bare JVM program records
 * fine). `pw-record` captures the mic correctly, like every other desktop tool,
 * and reads from the default source, so nothing is pinned to a specific device.
 *
 * [start] launches the recorder; [level] exposes a 0..1 loudness estimate for
 * the waveform UI (sampled from the tail of the growing file); [stop] ends
 * capture, finalizes the WAV, and returns it (or null if it was too short or
 * silent).
 */
class AudioRecorder {

    @Volatile
    var level: Float = 0f
        private set

    private var proc: Process? = null
    private var wav: File? = null
    private var meter: Thread? = null
    @Volatile private var recording = false

    /** True if pw-record is available to capture with. */
    fun isSupported(): Boolean = runCatching {
        ProcessBuilder("pw-record", "--help")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor(3, TimeUnit.SECONDS)
    }.getOrDefault(false)

    fun start() {
        val out = File.createTempFile("mutterboard-", ".wav").apply { delete() }
        proc = ProcessBuilder(
            "pw-record", "--rate", "16000", "--channels", "1", "--format", "s16",
            out.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        wav = out
        recording = true
        level = 0f
        // Drive the UI meter by sampling the tail of the file as pw-record grows it.
        meter = Thread {
            val buf = ByteArray(3200) // ~100ms at 16 kHz/16-bit mono
            while (recording) {
                runCatching {
                    val len = out.length()
                    if (len > 44L + buf.size) {
                        RandomAccessFile(out, "r").use { raf ->
                            raf.seek(len - buf.size)
                            val n = raf.read(buf)
                            if (n > 0) level = liveLevel(buf, n)
                        }
                    }
                }
                try { Thread.sleep(60) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
    }

    /** Stop capture and return the finalized WAV, or null if too short/silent. */
    fun stop(outDir: File): File? {
        recording = false
        meter?.interrupt()
        meter = null
        val p = proc
        val out = wav
        proc = null
        wav = null
        level = 0f
        if (p == null || out == null) return null

        // SIGINT lets pw-record write the final WAV header (SIGKILL would truncate
        // it), then wait for it to exit; force-kill only if it hangs.
        runCatching {
            ProcessBuilder("kill", "-INT", p.pid().toString()).start().waitFor(2, TimeUnit.SECONDS)
        }
        if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()

        val bytes = if (out.exists()) out.readBytes() else ByteArray(0)
        val dataLen = (bytes.size - WAV_HEADER).coerceAtLeast(0)
        // Under ~0.3s of audio is almost certainly an accidental tap; skip it.
        if (dataLen < 16_000 * 2 * 0.3) {
            out.delete()
            return null
        }
        // Near-silence -> no speech; refuse it so Whisper can't hallucinate a phrase.
        if (rmsFrom(bytes, WAV_HEADER) < SILENCE_RMS) {
            out.delete()
            return null
        }

        outDir.mkdirs()
        val dest = File(outDir, "dictation-${System.currentTimeMillis()}.wav")
        return if (out.renameTo(dest)) dest else out
    }

    /** RMS of the 16-bit samples starting at [offset] (0..full-scale). */
    private fun rmsFrom(bytes: ByteArray, offset: Int): Double {
        var sum = 0.0
        var i = offset
        var count = 0
        while (i + 1 < bytes.size) {
            val s = ((bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()
            sum += (s * s).toDouble()
            count++
            i += 2
        }
        return Math.sqrt(sum / count.coerceAtLeast(1))
    }

    /** RMS normalized to 0..1 for the waveform meter. */
    private fun liveLevel(bytes: ByteArray, len: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < len) {
            val s = ((bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()
            sum += (s * s).toDouble()
            i += 2
        }
        val count = (len / 2).coerceAtLeast(1)
        return (Math.sqrt(sum / count) / 8000.0).coerceIn(0.0, 1.0).toFloat()
    }

    private companion object {
        const val WAV_HEADER = 44
        // Voice runs ~1000+; an idle room sits well under this.
        const val SILENCE_RMS = 100.0
    }
}
