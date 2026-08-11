package com.mutterboard

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Ported from the Android app. Identical Groq call (whisper-large-v3-turbo,
 * en, temp 0, optional vocabulary prompt) so transcription quality matches the
 * phone exactly. The only thing dropped for desktop is the Opus streaming
 * encode path: the desktop recorder hands over a WAV and we upload it directly.
 *
 * Anything past the endpoint's upload cap is split into segments by
 * [WavSplitter] and uploaded in sequence, so a half-hour dictation goes through
 * as a single result instead of failing at the door.
 */
class GroqWhisperClient(private val apiKey: String) : Transcriber {

    private val client = OkHttpClient.Builder()
        // A whole-call ceiling, not just per-socket: without it a stalled
        // upload sits forever and the dictation never resolves either way.
        // Generous, because a ten-minute segment is a real upload.
        .callTimeout(4, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    /**
     * Optional vocabulary hint passed to Whisper as the `prompt` field so the
     * user's custom words (names, brands, jargon) come back spelled as expected.
     */
    @Volatile
    var vocabularyPrompt: String? = null

    /** Open a pooled TLS connection to Groq ahead of the upload. */
    override fun warmUp() {
        val request = Request.Builder().url("https://api.groq.com/").head().build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    override fun transcribe(audioFile: File, onResult: (Transcription) -> Unit) {
        Thread {
            val result = runCatching { transcribeBlocking(audioFile) }
                .getOrElse { Transcription.Failed(it.message ?: it.javaClass.simpleName) }
            onResult(result)
        }.apply { isDaemon = true; name = "whisper" }.start()
    }

    private fun transcribeBlocking(audioFile: File): Transcription {
        if (!WavSplitter.needsSplit(audioFile)) return upload(audioFile)

        val workDir = File(audioFile.parentFile, "segments")
        val parts = WavSplitter.split(audioFile, workDir)
        try {
            val pieces = mutableListOf<String>()
            parts.forEachIndexed { i, part ->
                when (val r = upload(part)) {
                    is Transcription.Failed ->
                        // One bad segment would silently swallow minutes of
                        // speech, so the whole take fails and stays retryable.
                        return Transcription.Failed("part ${i + 1} of ${parts.size}: ${r.reason}")
                    is Transcription.Text -> if (r.value.isNotBlank()) pieces += r.value.trim()
                }
            }
            return Transcription.Text(pieces.joinToString(" "))
        } finally {
            parts.filter { it != audioFile }.forEach { it.delete() }
            workDir.delete()
        }
    }

    /** One synchronous multipart upload. */
    private fun upload(wav: File): Transcription {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-large-v3-turbo")
            .addFormDataPart("language", "en")
            .addFormDataPart("temperature", "0")
            .apply {
                vocabularyPrompt?.takeIf { it.isNotBlank() }?.let {
                    addFormDataPart("prompt", it)
                }
            }
            .addFormDataPart(
                "file",
                wav.name,
                wav.asRequestBody("audio/wav".toMediaType()),
            )
            .addFormDataPart("response_format", "json")
            .build()

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                when {
                    !response.isSuccessful -> Transcription.Failed(describe(response.code, body))
                    body == null -> Transcription.Failed("empty response from Groq")
                    else -> runCatching { Transcription.Text(JSONObject(body).getString("text").trim()) }
                        .getOrElse { Transcription.Failed("unreadable response from Groq") }
                }
            }
        } catch (e: IOException) {
            Transcription.Failed(e.message ?: "network error")
        }
    }

    /** Turn an HTTP failure into something worth putting in front of a user. */
    private fun describe(code: Int, body: String?): String {
        val detail = body?.let {
            runCatching { JSONObject(it).getJSONObject("error").getString("message") }.getOrNull()
        }
        return when (code) {
            401, 403 -> "your Groq API key was rejected"
            413 -> "the recording was too large to upload"
            429 -> detail ?: "Groq rate limit reached — try again shortly"
            in 500..599 -> "Groq is having trouble (HTTP $code)"
            else -> detail ?: "Groq returned HTTP $code"
        }
    }

    override fun close() {
        val doomed = client
        Thread {
            doomed.dispatcher.executorService.shutdown()
            doomed.connectionPool.evictAll()
        }.start()
    }
}
