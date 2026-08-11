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
 * [start] records straight into the recordings directory — not a temp file —
 * so the audio is durable from the first second and survives a crash mid-take.
 * [level] exposes a 0..1 loudness estimate for the waveform UI (sampled from
 * the tail of the growing file); [stop] ends capture, finalizes the WAV, and
 * classifies the result.
 */
class AudioRecorder {

    /** What [stop] found. The audio file survives except in [TooShort]. */
    sealed interface Capture {
        /** Real audio, ready to transcribe. */
        data class Ok(val wav: File, val durationMs: Long) : Capture

        /** Under ~0.3s — an accidental tap. The file is deleted. */
        data object TooShort : Capture

        /**
         * Long enough to be deliberate but nothing above the noise floor,
         * which usually means the wrong input device. The file is KEPT: a
         * silent-looking take is exactly the case where a bad threshold would
         * throw away something the user actually said.
         */
        data class Silent(val wav: File, val durationMs: Long) : Capture
    }

    @Volatile
    var level: Float = 0f
        private set

    private var proc: Process? = null
    private var wav: File? = null
    private var meter: Thread? = null
    private var startedAt = 0L
    private var supported: Boolean? = null
    @Volatile private var recording = false

    /**
     * True if pw-record is available to capture with. Cached after the first
     * successful probe — this runs on the way into every dictation, and
     * spawning a process just to read `--help` is latency on the hot path.
     */
    fun isSupported(): Boolean {
        supported?.let { return it }
        val ok = runCatching {
            ProcessBuilder("pw-record", "--help")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor(3, TimeUnit.SECONDS)
        }.getOrDefault(false)
        if (ok) supported = true
        return ok
    }

    /** Begin capturing into [dest], which must not exist yet. */
    fun start(dest: File) {
        dest.parentFile?.mkdirs()
        proc = ProcessBuilder(
            "pw-record", "--rate", "16000", "--channels", "1", "--format", "s16",
            dest.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        wav = dest
        startedAt = System.currentTimeMillis()
        recording = true
        level = 0f
        // Drive the UI meter by sampling the tail of the file as pw-record grows it.
        meter = Thread {
            val buf = ByteArray(3200) // ~100ms at 16 kHz/16-bit mono
            while (recording) {
                runCatching {
                    val len = dest.length()
                    if (len > WAV_HEADER + buf.size) {
                        RandomAccessFile(dest, "r").use { raf ->
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

    /** Stop capture and classify what was recorded. */
    fun stop(): Capture {
        recording = false
        meter?.interrupt()
        meter = null
        val p = proc
        val out = wav
        val elapsed = System.currentTimeMillis() - startedAt
        proc = null
        wav = null
        level = 0f
        if (p == null || out == null) return Capture.TooShort

        finalize(p, out)

        val dataLen = (out.length() - WAV_HEADER).coerceAtLeast(0)
        val durationMs = (dataLen * 1000L) / (16_000 * 2)
        // Under ~0.3s of audio is almost certainly an accidental tap; skip it.
        // Measured on the file, not the wall clock, so a take whose audio went
        // missing is reported as too short rather than sent on as empty.
        if (dataLen < 16_000 * 2 * 0.3) {
            println("[audio] discarding ${dataLen}B after ${elapsed}ms — too short")
            out.delete()
            return Capture.TooShort
        }
        return if (isSilent(out)) {
            println("[audio] ${durationMs}ms captured but nothing above the noise floor")
            Capture.Silent(out, durationMs)
        } else {
            Capture.Ok(out, durationMs)
        }
    }

    /**
     * Bring pw-record down cleanly and make sure the header on disk describes
     * the audio that's actually there.
     *
     * SIGINT lets pw-record seek back and write the real data-chunk length
     * (SIGKILL would leave the placeholder). A long take can take a while to
     * flush, and the old 2-second budget was tight enough that big recordings
     * were getting killed mid-finalize — the header then claimed ~0 samples and
     * the whole dictation transcribed as nothing. So: a generous grace period,
     * and a header repair afterwards regardless, since a wrong length is
     * recoverable and silently losing the take is not.
     */
    private fun finalize(p: Process, out: File) {
        runCatching {
            ProcessBuilder("kill", "-INT", p.pid().toString())
                .start().waitFor(2, TimeUnit.SECONDS)
        }
        if (!p.waitFor(FINALIZE_GRACE_SECONDS, TimeUnit.SECONDS)) {
            println("[audio] pw-record did not exit in ${FINALIZE_GRACE_SECONDS}s; forcing")
            p.destroyForcibly()
            p.waitFor(2, TimeUnit.SECONDS)
        }
        repairHeader(out)
    }

    /**
     * Rewrite the RIFF and data chunk sizes from the file's real length when
     * they disagree with it. pw-record writes placeholders while recording and
     * patches them on exit; if that patch didn't land, the samples are all
     * still on disk and only these two little-endian ints are wrong.
     */
    private fun repairHeader(out: File) {
        runCatching {
            if (!out.isFile || out.length() < WAV_HEADER) return
            RandomAccessFile(out, "rw").use { raf ->
                val header = ByteArray(WAV_HEADER)
                raf.seek(0)
                if (raf.read(header) < WAV_HEADER) return
                if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") return
                val dataSizeAt = findDataSizeOffset(raf) ?: return
                val actualData = raf.length() - (dataSizeAt + 4)
                if (actualData <= 0) return

                if (readLeInt(raf, dataSizeAt) != actualData) {
                    println("[audio] repairing WAV header: data ${readLeInt(raf, dataSizeAt)} -> $actualData")
                    writeLeInt(raf, dataSizeAt, actualData)
                }
                val riff = raf.length() - 8
                if (readLeInt(raf, 4L) != riff) writeLeInt(raf, 4L, riff)
            }
        }.onFailure { println("[audio] header repair failed: ${it.message}") }
    }

    /**
     * Offset of the data chunk's 4-byte size field. Walks the chunk list rather
     * than assuming the canonical 44-byte layout, since a writer is free to put
     * a LIST or fact chunk ahead of the audio.
     */
    private fun findDataSizeOffset(raf: RandomAccessFile): Long? {
        var pos = 12L
        val id = ByteArray(4)
        while (pos + 8 <= raf.length()) {
            raf.seek(pos)
            if (raf.read(id) < 4) return null
            val size = readLeInt(raf, pos + 4)
            if (String(id) == "data") return pos + 4
            if (size <= 0) return null
            pos += 8 + size + (size and 1L) // chunks are word-aligned
        }
        return null
    }

    private fun readLeInt(raf: RandomAccessFile, at: Long): Long {
        raf.seek(at)
        val b = ByteArray(4)
        if (raf.read(b) < 4) return -1
        return (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
            ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
    }

    private fun writeLeInt(raf: RandomAccessFile, at: Long, value: Long) {
        raf.seek(at)
        raf.write(
            byteArrayOf(
                (value and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte(),
                ((value shr 16) and 0xFF).toByte(),
                ((value shr 24) and 0xFF).toByte(),
            ),
        )
    }

    /**
     * True when no window of the recording rises above the noise floor.
     *
     * Deliberately the *loudest* window rather than the whole-file average: a
     * long dictation is mostly pauses, and averaging over them drags a
     * perfectly good five-minute take below the threshold of a thirty-second
     * one. That mismeasurement was throwing away exactly the long recordings
     * this store exists to protect. Streams the file so a 30-minute take
     * doesn't land in memory.
     */
    private fun isSilent(wav: File): Boolean {
        var loudest = 0.0
        runCatching {
            wav.inputStream().buffered().use { input ->
                input.readNBytes(WAV_HEADER)
                val buf = ByteArray(WINDOW_BYTES)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    loudest = maxOf(loudest, rms(buf, n))
                    if (loudest >= SILENCE_RMS) return false
                }
            }
        }.onFailure {
            // Unreadable here means unreadable to the engine too, but the file
            // is what matters and it stays; let the transcriber have its go.
            println("[audio] could not scan for silence: ${it.message}")
            return false
        }
        return loudest < SILENCE_RMS
    }

    /** RMS of the first [len] bytes as 16-bit samples (0..full-scale). */
    private fun rms(bytes: ByteArray, len: Int): Double {
        var sum = 0.0
        var i = 0
        while (i + 1 < len) {
            val s = ((bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()
            sum += (s * s).toDouble()
            i += 2
        }
        return Math.sqrt(sum / (len / 2).coerceAtLeast(1))
    }

    /** RMS normalized to 0..1 for the waveform meter. */
    private fun liveLevel(bytes: ByteArray, len: Int): Float =
        (rms(bytes, len) / 8000.0).coerceIn(0.0, 1.0).toFloat()

    private companion object {
        const val WAV_HEADER = 44

        /**
         * How long pw-record gets to write its final header. Long recordings
         * need real time to flush; waiting here costs nothing when it exits
         * promptly, which it almost always does.
         */
        const val FINALIZE_GRACE_SECONDS = 15L

        /** ~200ms at 16 kHz/16-bit mono — long enough to span a syllable. */
        const val WINDOW_BYTES = 6400

        // Speech peaks well over 1000 within any 200ms window it occupies; a
        // quiet room stays far below this even at its loudest moment.
        const val SILENCE_RMS = 250.0
    }
}
