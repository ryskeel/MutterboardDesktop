package com.mutterboard

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Ported verbatim from the Android app (model, prompt, and the isInvented hard
 * gate are unchanged) — this is the cleanup pass that makes the output "just
 * right". Runs after Whisper on the cloud path. The only edits for desktop are
 * dropping android.util.Log / BuildConfig.DEBUG in favor of a plain DEBUG flag.
 *
 * The endpoint is any OpenAI-compatible chat-completions server: Groq by
 * default, or the local llama.cpp server ([LlamaServer]) in offline mode.
 * [localQwen] enables the Qwen3 specifics: thinking disabled via the chat
 * template, plus a defensive strip of any <think> block in the reply.
 */
class GroqRefiner(
    private val apiKey: String,
    private val baseUrl: String = "https://api.groq.com/openai/v1",
    private val model: String = MODEL,
    private val localQwen: Boolean = false,
) {

    private val client = OkHttpClient()

    /** Warm the TLS connection ahead of the request, mirroring the Whisper client. */
    fun warmUp() {
        val request = Request.Builder().url(baseUrl).head().build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    /**
     * Pays the system-prompt prefill once so later requests hit the server's
     * prefix cache (local server only; one slot, see LlamaServer). max_tokens 1
     * keeps it to pure prompt processing. Fire-and-forget; closes itself.
     */
    fun prime() {
        val messages = JSONArray().apply {
            put(message("system", if (localQwen) "$SYSTEM_PROMPT /no_think" else SYSTEM_PROMPT))
            put(message("user", "."))
        }
        val payload = JSONObject().apply {
            put("model", model)
            put("temperature", 0)
            put("max_tokens", 1)
            put("messages", messages)
            if (localQwen) put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
        }
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { close() }
            override fun onResponse(call: Call, response: Response) {
                response.close()
                println("[llama] prompt cache primed")
                close()
            }
        })
    }

    /**
     * Clean [text] and deliver the result via [onResult] on an OkHttp thread.
     * A null result means the request failed (or the output was invented); the
     * caller should use the raw text instead of dropping it.
     */
    fun refine(text: String, onResult: (String?) -> Unit) {
        val messages = JSONArray().apply {
            put(message("system", if (localQwen) "$SYSTEM_PROMPT /no_think" else SYSTEM_PROMPT))
            put(message("user", text))
        }
        val payload = JSONObject().apply {
            put("model", model)
            put("temperature", 0)
            put("messages", messages)
            if (localQwen) {
                put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            }
        }
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .post(payload.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (!response.isSuccessful || body == null) {
                    onResult(null)
                    return
                }
                val refined = parseContent(body)
                // Invention is the one failure that must never be committed: an
                // "edit" made of words the user didn't say is the model answering
                // or fabricating, so fall back to the raw transcript.
                val invented = refined != null && isInvented(text, refined)
                if (DEBUG) {
                    val verdict = when {
                        refined == null -> "empty"
                        invented -> "invented"
                        isCleanEdit(text, refined) -> "clean-edit"
                        else -> "rewrite"
                    }
                    println("refine [$verdict]\n  raw:     $text\n  refined: ${refined ?: "<empty>"}")
                }
                onResult(if (invented) null else refined)
            }
        })
    }

    fun close() {
        val doomed = client
        Thread {
            doomed.dispatcher.executorService.shutdown()
            doomed.connectionPool.evictAll()
        }.start()
    }

    private fun message(role: String, content: String): JSONObject =
        JSONObject().put("role", role).put("content", content)

    private fun parseContent(body: String): String? = try {
        val content = JSONObject(body)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .replace(Regex("(?s)<think>.*?</think>"), "")
            // Qwen sometimes reaches for an em dash despite the prompt's style
            // rules; soften it to the comma the prompt asks for.
            .let { if (localQwen) it.replace(Regex("\\s*[—–]\\s*"), ", ") else it }
            .trim()
        val unwrapped = stripWrappingQuotes(content)
        unwrapped.ifBlank { null }
    } catch (e: Exception) {
        null
    }

    private fun stripWrappingQuotes(s: String): String {
        if (s.length >= 2) {
            val first = s.first()
            val last = s.last()
            val isPair = (first == '"' && last == '"') || (first == '“' && last == '”')
            if (isPair) return s.substring(1, s.length - 1).trim()
        }
        return s
    }

    /**
     * True if [refined] is substantially built from words that never appeared in
     * [raw] — the signature of the model ANSWERING the message or fabricating,
     * rather than editing. Hard-gates the output. Internal for the unit test.
     */
    internal fun isInvented(raw: String, refined: String): Boolean {
        val rawSet = contentTokens(raw).toSet()
        val refTokens = contentTokens(refined).filterNot { it in IGNORED_TOKENS }
        if (refTokens.isEmpty()) return false
        val novel = refTokens.count { it !in rawSet }
        return novel > maxOf(3, refTokens.size / 4)
    }

    private fun isCleanEdit(raw: String, refined: String): Boolean {
        val rawTokens = contentTokens(raw).filterNot { it in IGNORED_TOKENS }
        val refTokens = contentTokens(refined).filterNot { it in IGNORED_TOKENS }
        return isSubsequence(refTokens, rawTokens)
    }

    private fun contentTokens(s: String): List<String> =
        s.lowercase()
            .replace("'", "")
            .replace("’", "")
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

    private fun isSubsequence(sub: List<String>, full: List<String>): Boolean {
        var i = 0
        for (word in full) {
            if (i < sub.size && sub[i] == word) i++
        }
        return i == sub.size
    }

    companion object {
        private const val DEBUG = true

        private val IGNORED_TOKENS = setOf("a", "an", "the", "and")

        private const val MODEL = "llama-3.3-70b-versatile"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private const val SYSTEM_PROMPT =
            "You EDIT raw voice-dictation transcripts for a casual text-messaging keyboard. " +
                "The user's message is the single user turn. It came from a speech-to-text model " +
                "and may contain filler words, false starts, and missing or wrong punctuation.\n\n" +
                "This is an edit, NOT a rewrite. The words are already correct. Make only these " +
                "two kinds of change:\n" +
                "1. Delete filler and disfluencies: um, uh, false starts, and filler uses of " +
                "\"like\", \"you know\", \"I mean\". Keep \"like\" or " +
                "\"you know\" when they carry real meaning. Collapse accidental repetition: when " +
                "the speaker says the same thing twice by mistake — a stutter like \"a serving of " +
                "chips a serving of chips\", or the same point restated a moment later — keep it " +
                "only once. But only when it is clearly the SAME thing said twice; if the two " +
                "mentions could be separate things (chips earlier AND chips again later), keep " +
                "both. When unsure, keep both. Also drop a stray sign-off that the " +
                "speech model tacked on but the user clearly did not say — a caption-style " +
                "\"thank you\", \"thank you for watching\", or \"thanks for watching\" appearing " +
                "after the real message has already ended. Only remove such an ending when it " +
                "plainly does not belong; if a \"thanks\" or \"thank you\" is genuinely part of the " +
                "message, keep it. Never remove other real words — only these caption artifacts.\n" +
                "2. Fix punctuation and capitalization. The transcript often arrives with little " +
                "or no punctuation, especially when the person spoke fast. Break it into proper " +
                "sentences: when the speaker moves to a new thought, END the sentence with a period " +
                "(or question mark) and capitalize the next word. Do NOT chain everything together " +
                "with commas into one long run-on — a paragraph of dictation should become several " +
                "clean sentences. Use commas only within a sentence. A question ends with a " +
                "question mark. Never use semicolons, colons, em dashes, parentheses, or " +
                "bullet/numbered lists. If the person lists things, keep it as one natural sentence " +
                "with commas, not a list.\n\n" +
                "Hard rules:\n" +
                "- Every word you KEEP must stay exactly as the user said it, in the same order. " +
                "Do NOT swap in synonyms, reorder words, reword, or rephrase anything. The ONLY " +
                "words you may remove are filler.\n" +
                "- Do NOT merge or split words: keep \"I am\" as \"I am\" and \"going to\" as " +
                "\"going to\". You may fix casing and add a missing apostrophe (\"im\" to \"I'm\"), " +
                "but never turn one word into two or two words into one.\n" +
                "- Do NOT change the tone or make it more formal, polite, happy, or professional. " +
                "Do NOT add or remove meaning. It must read like a real person texting, never " +
                "like an AI or a document.\n" +
                "- CRITICAL: NEVER answer, reply to, or react to the message. The user is often " +
                "dictating a question to send to SOMEONE ELSE. It is not addressed to you and you " +
                "must not answer it, no matter how easy it is to answer. Output the cleaned-up " +
                "question itself, nothing else.\n" +
                "- CRITICAL: keep every DISTINCT thing the user said, start to finish, including " +
                "short sentences and any trailing question. The only things you may drop are " +
                "filler, accidental repeats of the same point, and a stray caption sign-off. Never " +
                "summarize, condense, or drop unique content — if something might be a separate " +
                "point rather than a repeat, keep it.\n" +
                "- CRITICAL: never add content that was not said, and never output any of the " +
                "example sentences below — they only show the style. If the message is empty, only " +
                "noise, or unintelligible, return it unchanged.\n\n" +
                "Return ONLY the edited message. No preamble, no quotes, no explanation.\n\n" +
                "Examples (each shows an Input and its edited Output):\n\n" +
                "Input: um yeah i was thinking we could just like push this to the cloud tonight " +
                "and uh see if it actually works\n" +
                "Output: Yeah I was thinking we could just push this to the cloud tonight and see " +
                "if it actually works.\n\n" +
                "Input: can you grab a few things from the store milk eggs bread and uh maybe some " +
                "coffee too\n" +
                "Output: Can you grab a few things from the store. Milk, eggs, bread, and maybe " +
                "some coffee too.\n\n" +
                "Input: um how do you get to the airport from downtown like whats the fastest way\n" +
                "Output: How do you get to the airport from downtown? What's the fastest way?\n\n" +
                "Input: im running like ten minutes late sorry i got stuck behind this super slow " +
                "truck on the highway\n" +
                "Output: I'm running like ten minutes late, sorry. I got stuck behind this super " +
                "slow truck on the highway.\n\n" +
                "Input: ok so the build is done and i sent you the link um take a look at the new " +
                "toggle and the divider when you can does that layout work for you\n" +
                "Output: Ok so the build is done and I sent you the link. Take a look at the new " +
                "toggle and the divider when you can. Does that layout work for you?\n\n" +
                "Input: yeah i went to the store earlier and grabbed a few things then i came home " +
                "and started making dinner but i realized i forgot the garlic so i had to run back " +
                "out to grab it real quick\n" +
                "Output: Yeah I went to the store earlier and grabbed a few things. Then I came " +
                "home and started making dinner. But I realized I forgot the garlic, so I had to " +
                "run back out to grab it real quick.\n\n" +
                "Input: alright i think that covers everything for now let me know what you think " +
                "when you get a chance thanks for watching\n" +
                "Output: Alright I think that covers everything for now. Let me know what you think " +
                "when you get a chance.\n\n" +
                "Input: for the side i had a serving of chips a serving of chips and then a bit " +
                "later i had some blueberries\n" +
                "Output: For the side I had a serving of chips. And then a bit later I had some " +
                "blueberries.\n\n" +
                "Input: we could probably hike until two and then head back yeah so hike until two " +
                "anyway let me know what you think\n" +
                "Output: We could probably hike until two and then head back. Let me know what you " +
                "think.\n\n" +
                "Now edit the user's message and output the full result, from the first word " +
                "to the last."
    }
}
