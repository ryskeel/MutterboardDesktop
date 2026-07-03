package com.mutterboard

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * Captures microphone audio as 16 kHz mono 16-bit PCM — the format Whisper
 * wants — using javax.sound.sampled, which rides on PipeWire's ALSA/Pulse
 * compat on Bazzite. Replaces the Android app's AudioRecord + WavRecorder.
 *
 * [start] opens the line and reads on a background thread; [level] exposes a
 * 0..1 loudness estimate for the waveform UI; [stop] closes the line and writes
 * the captured PCM to a real .wav file, returning it (or null if nothing/too
 * little was captured).
 */
class AudioRecorder {

    private val format = AudioFormat(16_000f, 16, 1, true, false) // signed, little-endian

    @Volatile
    var level: Float = 0f
        private set

    private var line: TargetDataLine? = null
    private var thread: Thread? = null
    private val buffer = ByteArrayOutputStream()

    /** True if a microphone capture line is available on this machine. */
    fun isSupported(): Boolean =
        AudioSystem.isLineSupported(DataLine.Info(TargetDataLine::class.java, format))

    fun start() {
        val info = DataLine.Info(TargetDataLine::class.java, format)
        val l = AudioSystem.getLine(info) as TargetDataLine
        l.open(format)
        l.start()
        line = l
        buffer.reset()
        val t = Thread {
            val chunk = ByteArray(3200) // ~100ms at 16kHz/16-bit mono
            while (!Thread.currentThread().isInterrupted) {
                val n = l.read(chunk, 0, chunk.size)
                if (n <= 0) break
                buffer.write(chunk, 0, n)
                level = rms(chunk, n)
            }
        }
        t.isDaemon = true
        t.start()
        thread = t
    }

    /** Stop capture and write a WAV. Returns the file, or null if too short. */
    fun stop(outDir: File): File? {
        thread?.interrupt()
        line?.let {
            it.stop()
            it.close()
        }
        thread = null
        line = null
        level = 0f

        val pcm = buffer.toByteArray()
        // Under ~0.3s of audio is almost certainly an accidental tap; skip it.
        if (pcm.size < 16_000 * 2 * 0.3) return null

        outDir.mkdirs()
        val out = File(outDir, "dictation-${System.currentTimeMillis()}.wav")
        val frames = (pcm.size / format.frameSize).toLong()
        AudioInputStream(ByteArrayInputStream(pcm), format, frames).use { ais ->
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, out)
        }
        return out
    }

    private fun rms(bytes: ByteArray, len: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < len) {
            val sample = (bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)
            val s = sample.toShort().toInt()
            sum += (s * s).toDouble()
            i += 2
        }
        val count = (len / 2).coerceAtLeast(1)
        val rms = Math.sqrt(sum / count)
        // Normalize against a modest reference so speech lands mid-range.
        return (rms / 8000.0).coerceIn(0.0, 1.0).toFloat()
    }
}
