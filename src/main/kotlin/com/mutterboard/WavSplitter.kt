package com.mutterboard

import java.io.File
import java.io.RandomAccessFile

/**
 * Splits an over-long recording into pieces Parakeet can decode, cutting at
 * the quietest moment near each boundary so a segment edge rarely lands
 * mid-word.
 *
 * Parakeet's encoder has a fixed positional-encoding table of ~5000 frames
 * (80 ms each), so anything past about 6:40 aborts sherpa-onnx outright with
 * an onnxruntime broadcast error — a 6:48 take failed exactly that way. Pieces
 * stay well under it.
 *
 * Everything the recorder produces is raw PCM behind a fixed header, so a
 * "split" is a byte-range copy with a fresh 44-byte header on the front.
 */
object WavSplitter {

    const val HEADER = 44
    private const val BYTES_PER_SECOND = 16_000 * 2

    /** Takes up to this long decode whole; comfortably under the ~400s limit. */
    private const val MAX_WHOLE_BYTES = 300L * BYTES_PER_SECOND

    /** Segments target 4 minutes; with the search window a piece tops out at 4:15. */
    private const val SEGMENT_BYTES = 240L * BYTES_PER_SECOND

    /** How far on either side of a target cut to hunt for a quiet moment. */
    private const val SEARCH_BYTES = 15L * BYTES_PER_SECOND

    /** ~100ms — the window whose energy decides how quiet a candidate cut is. */
    private const val PROBE_BYTES = 3200

    fun needsSplit(wav: File): Boolean = wav.length() - HEADER > MAX_WHOLE_BYTES

    /**
     * Cut [wav] into segment files inside [workDir], in order. Returns a
     * single-element list holding the original when it already fits. The
     * caller owns the returned files and should delete any that aren't the
     * original once it's done.
     */
    fun split(wav: File, workDir: File): List<File> {
        if (!needsSplit(wav)) return listOf(wav)
        workDir.mkdirs()

        val dataLen = (wav.length() - HEADER).coerceAtLeast(0)
        val out = mutableListOf<File>()
        RandomAccessFile(wav, "r").use { raf ->
            var start = 0L
            var n = 0
            while (start < dataLen) {
                val target = start + SEGMENT_BYTES
                val end = if (target >= dataLen) dataLen else quietestCut(raf, target, dataLen)
                val piece = File(workDir, "${wav.nameWithoutExtension}-part${n++}.wav")
                writeSegment(raf, start, end, piece)
                out += piece
                start = end
            }
        }
        println("[split] ${wav.name} (${dataLen / BYTES_PER_SECOND}s) -> ${out.size} segments")
        return out
    }

    /**
     * Byte offset of the quietest ~100ms window within the search range around
     * [target], snapped to a sample boundary. Falls back to [target] itself if
     * nothing can be read — a cut mid-word costs a garbled word, which beats
     * failing the whole take.
     */
    private fun quietestCut(raf: RandomAccessFile, target: Long, dataLen: Long): Long {
        val from = (target - SEARCH_BYTES).coerceAtLeast(0)
        val to = (target + SEARCH_BYTES).coerceAtMost(dataLen - PROBE_BYTES)
        if (to <= from) return target

        var bestAt = target
        var bestEnergy = Double.MAX_VALUE
        val buf = ByteArray(PROBE_BYTES)
        var at = from
        while (at < to) {
            val energy = runCatching {
                raf.seek(HEADER + at)
                val n = raf.read(buf)
                if (n <= 0) null else rms(buf, n)
            }.getOrNull()
            if (energy != null && energy < bestEnergy) {
                bestEnergy = energy
                bestAt = at
            }
            at += PROBE_BYTES
        }
        return bestAt - (bestAt % 2)
    }

    /** Copy the PCM range [from,to) out to [dest] behind a fresh WAV header. */
    private fun writeSegment(raf: RandomAccessFile, from: Long, to: Long, dest: File) {
        val length = to - from
        dest.outputStream().buffered().use { out ->
            out.write(header(length))
            raf.seek(HEADER + from)
            val buf = ByteArray(256 * 1024)
            var left = length
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
    }

    /** Canonical 44-byte header for 16 kHz mono 16-bit PCM of [dataLen] bytes. */
    private fun header(dataLen: Long): ByteArray {
        val h = ByteArray(HEADER)
        fun ascii(at: Int, s: String) = s.forEachIndexed { i, c -> h[at + i] = c.code.toByte() }
        fun le32(at: Int, v: Long) {
            h[at] = (v and 0xFF).toByte()
            h[at + 1] = ((v shr 8) and 0xFF).toByte()
            h[at + 2] = ((v shr 16) and 0xFF).toByte()
            h[at + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun le16(at: Int, v: Int) {
            h[at] = (v and 0xFF).toByte()
            h[at + 1] = ((v shr 8) and 0xFF).toByte()
        }
        ascii(0, "RIFF"); le32(4, dataLen + 36); ascii(8, "WAVE")
        ascii(12, "fmt "); le32(16, 16); le16(20, 1) // PCM
        le16(22, 1) // mono
        le32(24, 16_000)
        le32(28, BYTES_PER_SECOND.toLong())
        le16(32, 2) // block align
        le16(34, 16) // bits per sample
        ascii(36, "data"); le32(40, dataLen)
        return h
    }

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
}
