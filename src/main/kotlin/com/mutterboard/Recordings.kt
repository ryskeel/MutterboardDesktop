package com.mutterboard

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * How a take ended. The audio is on disk for every one of these, so any state
 * other than [DONE] can be retried from the History card.
 */
enum class TakeStatus {
    /** Recorded, transcription not finished (or the app died mid-flight). */
    PENDING,

    /** Transcribed and delivered to the clipboard. */
    DONE,

    /** The engine failed. The WAV is still here; re-transcribe recovers it. */
    FAILED,
}

/**
 * One dictation: the audio file plus whatever text we got out of it.
 *
 * [raw] is the transcript as the engine produced it and [text] is the refined
 * result that was actually pasted. Both are kept because the refiner sometimes
 * loses a word the user wanted, and the raw version is the fallback the user
 * can copy instead.
 */
data class Take(
    val id: String,
    val at: Long,
    val durationMs: Long,
    val audio: String,
    val status: TakeStatus,
    val raw: String = "",
    val text: String = "",
    val engine: String = "",
    val error: String = "",
) {
    /** Absolute path of the audio, or null once the file has been pruned. */
    fun audioFile(store: RecordingStore): File? =
        File(store.audioDir, audio).takeIf { it.isFile }

    /** "2:04" / "0:12" — the duration as the History card shows it. */
    val duration: String
        get() {
            val total = durationMs / 1000
            return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
        }

    /** "Today 4:12 PM" / "Mar 3 4:12 PM", in the user's zone. */
    fun timestamp(now: Long = System.currentTimeMillis()): String {
        val zone = ZoneId.systemDefault()
        val then = Instant.ofEpochMilli(at).atZone(zone)
        val clock = then.format(TIME)
        return when (then.toLocalDate()) {
            Instant.ofEpochMilli(now).atZone(zone).toLocalDate() -> "Today $clock"
            Instant.ofEpochMilli(now).atZone(zone).minusDays(1).toLocalDate() -> "Yesterday $clock"
            else -> "${then.format(DATE)} $clock"
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("at", at)
        .put("durationMs", durationMs)
        .put("audio", audio)
        .put("status", status.name)
        .put("raw", raw)
        .put("text", text)
        .put("engine", engine)
        .put("error", error)

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("h:mm a")
        private val DATE = DateTimeFormatter.ofPattern("MMM d")

        fun fromJson(o: JSONObject): Take = Take(
            id = o.getString("id"),
            at = o.optLong("at"),
            durationMs = o.optLong("durationMs"),
            audio = o.optString("audio"),
            status = runCatching { TakeStatus.valueOf(o.optString("status")) }
                .getOrDefault(TakeStatus.PENDING),
            raw = o.optString("raw"),
            text = o.optString("text"),
            engine = o.optString("engine"),
            error = o.optString("error"),
        )
    }
}

/**
 * Every dictation, kept on disk: the WAV under
 * ~/.local/share/mutterboard/recordings/ and an index in history.json beside
 * it. This is the app's durability guarantee — the recording lands here the
 * moment the mic opens and is only ever removed by pruning or an explicit
 * delete, never by a transcription failure.
 *
 * The index is written whole on each change (a few hundred entries at most)
 * via a temp file + atomic rename, so a crash mid-write can't corrupt it. All
 * mutations are synchronized: the pipeline touches this from OkHttp callbacks,
 * the transcriber's worker thread, and the UI thread.
 */
class RecordingStore(private val retention: () -> Retention) {

    /** How much history to keep. Pruning runs after every completed take. */
    data class Retention(val maxTakes: Int, val maxDays: Int)

    val dir: File = File(OfflinePaths.dataDir, "recordings")
    val audioDir: File = File(dir, "audio")
    private val index = File(dir, "history.json")
    private val lock = Any()

    /** Newest first. */
    @Volatile
    var takes: List<Take> = emptyList()
        private set

    /** Called after any change so the controller can republish to Compose. */
    @Volatile
    var onChange: (List<Take>) -> Unit = {}

    init {
        audioDir.mkdirs()
        takes = load()
    }

    /**
     * A fresh audio path to record into. The file does not exist yet; the
     * recorder creates it, and [add] indexes it once capture stops. Anything
     * left here without an index entry is swept up by [reconcile].
     */
    fun newAudioFile(id: String): File = File(audioDir, "$id.wav")

    fun newId(): String = "take-${System.currentTimeMillis()}"

    /** Index a finished recording as PENDING, before transcription starts. */
    fun add(take: Take) = mutate { listOf(take) + it }

    /** Replace one entry by id; a no-op if it has since been deleted. */
    fun update(id: String, edit: (Take) -> Take) = mutate { list ->
        list.map { if (it.id == id) edit(it) else it }
    }

    /**
     * Re-apply the retention window now, for when the user tightens it in
     * settings rather than waiting for the next take to trigger a prune.
     */
    fun applyRetention() = mutate { it }

    fun get(id: String): Take? = takes.firstOrNull { it.id == id }

    /** Drop one take and its audio. */
    fun delete(id: String) = mutate { list ->
        list.firstOrNull { it.id == id }?.let { File(audioDir, it.audio).delete() }
        list.filterNot { it.id == id }
    }

    fun clear() = mutate { list ->
        list.forEach { File(audioDir, it.audio).delete() }
        emptyList()
    }

    /** Total bytes of retained audio, for the "using N MB" line in settings. */
    fun bytesOnDisk(): Long =
        audioDir.listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * Startup repair. Two things can be stale after a crash: an entry stuck in
     * PENDING (the app died between recording and delivery) becomes FAILED so
     * the History card offers it for re-transcription, and an orphan WAV with
     * no entry gets adopted so the audio is still reachable. Pruning then
     * applies the retention window to whatever survived.
     */
    fun reconcile() = mutate { list ->
        val recovered = list.map {
            if (it.status == TakeStatus.PENDING) {
                it.copy(status = TakeStatus.FAILED, error = "Interrupted — never finished.")
            } else {
                it
            }
        }
        val indexed = recovered.map { it.audio }.toSet()
        val orphans = (audioDir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".wav") && it.name !in indexed }
            .map { file ->
                Take(
                    id = file.nameWithoutExtension,
                    at = file.lastModified(),
                    durationMs = durationOf(file),
                    audio = file.name,
                    status = TakeStatus.FAILED,
                    error = "Recovered from disk.",
                )
            }
        (recovered + orphans).sortedByDescending { it.at }
    }

    /** Duration implied by a 16 kHz mono 16-bit WAV's size on disk. */
    fun durationOf(wav: File): Long =
        ((wav.length() - WAV_HEADER).coerceAtLeast(0) * 1000L) / (16_000 * 2)

    private fun mutate(edit: (List<Take>) -> List<Take>) {
        val published = synchronized(lock) {
            val next = prune(edit(takes))
            takes = next
            save(next)
            next
        }
        onChange(published)
    }

    /**
     * Enforce the retention window. Age is checked first so a burst of takes
     * can't push out recent ones by count alone, and the audio of anything
     * dropped goes with it.
     */
    private fun prune(list: List<Take>): List<Take> {
        val (maxTakes, maxDays) = retention()
        val cutoff = System.currentTimeMillis() - maxDays * 24L * 60 * 60 * 1000
        val kept = list
            .filter { maxDays <= 0 || it.at >= cutoff }
            .let { if (maxTakes > 0) it.take(maxTakes) else it }
        val keptIds = kept.map { it.id }.toSet()
        list.filterNot { it.id in keptIds }.forEach { File(audioDir, it.audio).delete() }
        return kept
    }

    private fun load(): List<Take> = runCatching {
        if (!index.isFile) return@runCatching emptyList()
        val arr = JSONArray(index.readText())
        (0 until arr.length())
            .mapNotNull { runCatching { Take.fromJson(arr.getJSONObject(it)) }.getOrNull() }
            .sortedByDescending { it.at }
    }.getOrElse {
        println("[history] unreadable index (${it.message}); starting fresh")
        emptyList()
    }

    private fun save(list: List<Take>) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "history.json.tmp")
            tmp.writeText(JSONArray(list.map { it.toJson() }).toString(2))
            if (!tmp.renameTo(index)) {
                index.delete()
                tmp.renameTo(index)
            }
        }.onFailure { println("[history] could not save index: ${it.message}") }
    }

    private companion object {
        const val WAV_HEADER = 44
    }
}
