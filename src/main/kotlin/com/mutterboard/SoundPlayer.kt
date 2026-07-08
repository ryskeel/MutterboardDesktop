package com.mutterboard

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Plays the recording start/stop cues. The two pops are synthesized once into
 * the cache dir (a short sine blip whose pitch sweeps up for "start", down for
 * "stop") and played through pw-play — javax.sound is unreliable in this GUI
 * process (see AudioRecorder), so playback goes through PipeWire directly too.
 * Volume is applied per play via pw-play's linear --volume flag.
 */
class SoundPlayer(private val cacheDir: File) {

    fun playStart(volume: Float) = play(ensurePop("pop-start.wav", up = true), volume)
    fun playStop(volume: Float) = play(ensurePop("pop-stop.wav", up = false), volume)

    private fun play(wav: File, volume: Float) {
        if (volume <= 0f) return
        Thread {
            runCatching {
                ProcessBuilder(
                    "pw-play", "--volume", volume.coerceIn(0f, 1f).toString(),
                    wav.absolutePath,
                ).start()
            }.onFailure { println("[sound] pw-play failed: ${it.message}") }
        }.apply { isDaemon = true }.start()
    }

    private fun ensurePop(name: String, up: Boolean): File {
        val file = File(cacheDir, name)
        if (!file.exists()) {
            cacheDir.mkdirs()
            file.writeBytes(synthesizePop(up))
        }
        return file
    }

    /**
     * A ~140 ms "pop": a sine blip with a fast attack and exponential decay,
     * pitch gliding 520→940 Hz on start and the reverse on stop, so the two
     * cues are unmistakably different by ear.
     */
    private fun synthesizePop(up: Boolean): ByteArray {
        val rate = 44100
        val samples = (rate * 0.14).toInt()
        val (from, to) = if (up) 520.0 to 940.0 else 940.0 to 520.0
        var phase = 0.0
        val pcm = ShortArray(samples)
        for (i in 0 until samples) {
            val t = i.toDouble() / samples
            val freq = from + (to - from) * t
            phase += 2 * PI * freq / rate
            // 4 ms linear attack to avoid a click, then exponential decay.
            val attack = (i / (rate * 0.004)).coerceAtMost(1.0)
            val envelope = attack * exp(-5.0 * t)
            pcm[i] = (sin(phase) * envelope * Short.MAX_VALUE * 0.85).toInt().toShort()
        }
        return wavBytes(pcm, rate)
    }

    /** Wraps mono 16-bit PCM in a minimal RIFF/WAVE header. */
    private fun wavBytes(pcm: ShortArray, rate: Int): ByteArray {
        val data = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            data[i * 2] = (pcm[i].toInt() and 0xFF).toByte()
            data[i * 2 + 1] = (pcm[i].toInt() shr 8).toByte()
        }
        val out = ByteArrayOutputStream()
        fun le32(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun le16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        out.write("RIFF".toByteArray()); le32(36 + data.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1)
        le32(rate); le32(rate * 2); le16(2); le16(16)
        out.write("data".toByteArray()); le32(data.size); out.write(data)
        return out.toByteArray()
    }
}
