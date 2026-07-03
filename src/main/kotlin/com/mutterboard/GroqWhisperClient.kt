package com.mutterboard

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Ported from the Android app. Identical Groq call (whisper-large-v3-turbo,
 * en, temp 0, optional vocabulary prompt) so transcription quality matches the
 * phone exactly. The only thing dropped for desktop v0.1 is the Opus streaming
 * encode path: the desktop recorder hands over a WAV and we upload it directly.
 */
class GroqWhisperClient(private val apiKey: String) : Transcriber {

    private val client = OkHttpClient()

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

    override fun transcribe(audioFile: File, onResult: (String?) -> Unit) {
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
                audioFile.name,
                audioFile.asRequestBody("audio/wav".toMediaType()),
            )
            .addFormDataPart("response_format", "json")
            .build()

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    try {
                        onResult(JSONObject(body).getString("text").trim())
                    } catch (e: Exception) {
                        onResult(null)
                    }
                } else {
                    onResult(null)
                }
            }
        })
    }

    override fun close() {
        val doomed = client
        Thread {
            doomed.dispatcher.executorService.shutdown()
            doomed.connectionPool.evictAll()
        }.start()
    }
}
