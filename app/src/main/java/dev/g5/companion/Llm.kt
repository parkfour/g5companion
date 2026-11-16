package dev.g5.companion

import android.util.Base64
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaWebSearchTool20250305
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.GoogleSearch
import com.google.genai.types.Part
import com.google.genai.types.Tool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One question to whichever AI service is active. Blocking; call from Dispatchers.IO. */
object Llm {
    data class Reply(val heard: String?, val answer: String)

    private const val HEARD_INSTRUCTION =
        "\n\nThe question is in the attached audio. Start your reply with one line `HEARD: <the question, transcribed>`, " +
            "then answer on the following lines."

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    private var gemini: Pair<String, Client>? = null
    private var claude: Pair<String, AnthropicClient>? = null

    /**
     * [audio] (16 kHz WAV) is only sent to Gemini, which understands speech natively; for every other provider the
     * caller passes the on-device transcript as [question].
     */
    fun ask(prefs: Prefs, system: String, question: String, image: ByteArray?, audio: ByteArray?): Reply {
        val p = prefs.provider
        val key = prefs.apiKey(p)
        if (key.isNullOrBlank() && p != Provider.CUSTOM) throw IOException("Add your ${p.label} API key in settings first")
        val model = prefs.model(p).ifBlank { throw IOException("Set a model name for ${p.label} in settings") }
        return when (p) {
            Provider.GEMINI -> askGemini(key!!, model, system, question, image, audio)
            Provider.CLAUDE -> Reply(null, askClaude(key!!, model, system, question, image))
            Provider.OPENAI -> Reply(null, askOpenAi(key!!, model, system, question, image))
            Provider.CUSTOM -> Reply(null, askCompatible(prefs.customUrl, key, model, system, question, image))
        }.let { it.copy(answer = speakable(it.answer)) }
    }

    // ---------- Gemini (google-genai SDK, Google Search grounding) ----------

    private fun askGemini(key: String, model: String, system: String, question: String, image: ByteArray?, audio: ByteArray?): Reply {
        val client = gemini?.takeIf { it.first == key }?.second
            ?: Client.builder().apiKey(key).build().also { gemini?.second?.close(); gemini = key to it }
        val parts = buildList {
            image?.let { add(Part.fromBytes(it, "image/jpeg")) }
            audio?.let { add(Part.fromBytes(it, "audio/wav")) }
            add(Part.fromText(question))
        }
        val config = GenerateContentConfig.builder()
            .systemInstruction(Content.fromParts(Part.fromText(system + if (audio != null) HEARD_INSTRUCTION else "")))
            .tools(Tool.builder().googleSearch(GoogleSearch.builder().build()).build())
            .build()
        val text = client.models.generateContent(model, Content.fromParts(*parts.toTypedArray()), config).text().orEmpty().trim()
        if (audio == null) return Reply(null, text)
        val first = text.lineSequence().firstOrNull().orEmpty()
        return if (first.startsWith("HEARD:", ignoreCase = true)) {
            Reply(first.substringAfter(':').trim().trim('`'), text.substringAfter('\n', "").trim())
        } else Reply(null, text)
    }

    // ---------- Claude (official Anthropic Java SDK, server-side web search) ----------

    private fun askClaude(key: String, model: String, system: String, question: String, image: ByteArray?): String {
        val client = claude?.takeIf { it.first == key }?.second
            ?: AnthropicOkHttpClient.builder().apiKey(key).build().also { claude?.second?.close(); claude = key to it }
        val blocks = buildList {
            image?.let {
                add(BetaContentBlockParam.ofImage(
                    BetaBase64ImageSource.builder().data(Base64.encodeToString(it, Base64.NO_WRAP))
                        .mediaType(BetaBase64ImageSource.MediaType.IMAGE_JPEG).build(),
                ))
            }
            add(BetaContentBlockParam.ofText(question))
        }
        // Haiku has neither the dynamic-filtering search tool nor server-side refusal fallbacks.
        val haiku = "haiku" in model
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16_000L)
            .system(system)
            .addUserMessageOfContentBlockParams(blocks)
            // Spoken Q&A is latency-sensitive and does well at low effort.
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
        if (haiku) {
            builder.addTool(BetaWebSearchTool20250305.builder().maxUses(3L).build())
        } else {
            builder.addTool(BetaWebSearchTool20260209.builder().maxUses(3L).build())
                .addBeta("server-side-fallback-2026-07-01").fallbacksDefault()
        }
        val text = StringBuilder()
        // pause_turn: a long server-side search loop paused; resend with the partial turn to let it finish.
        repeat(3) {
            val msg = client.beta().messages().create(builder.build())
            msg.content().forEach { b -> b.text().ifPresent { text.append(it.text()) } }
            when (msg.stopReason().orElse(null)) {
                BetaStopReason.PAUSE_TURN -> builder.addMessage(msg)
                BetaStopReason.REFUSAL -> return text.toString().ifBlank { "Sorry, I can't help with that one." }
                else -> return text.toString()
            }
        }
        return text.toString()
    }

    // ---------- OpenAI (Responses API, built-in web search) ----------

    private fun askOpenAi(key: String, model: String, system: String, question: String, image: ByteArray?): String {
        val content = JSONArray().put(JSONObject().put("type", "input_text").put("text", question))
        image?.let { content.put(JSONObject().put("type", "input_image").put("image_url", dataUrl(it))) }
        val body = JSONObject()
            .put("model", model)
            .put("instructions", system)
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
        val json = post("https://api.openai.com/v1/responses", key, body)
        val out = json.optJSONArray("output") ?: JSONArray()
        return buildString {
            for (i in 0 until out.length()) {
                val item = out.getJSONObject(i)
                if (item.optString("type") != "message") continue
                val parts = item.optJSONArray("content") ?: continue
                for (j in 0 until parts.length()) parts.getJSONObject(j).takeIf { it.optString("type") == "output_text" }?.let { append(it.optString("text")) }
            }
        }
    }

    // ---------- Any OpenAI-compatible /chat/completions endpoint ----------

    private fun askCompatible(baseUrl: String, key: String?, model: String, system: String, question: String, image: ByteArray?): String {
        if (baseUrl.isBlank()) throw IOException("Set the custom service URL in settings")
        fun body(withImage: Boolean): JSONObject {
            val user: Any = if (withImage && image != null) {
                JSONArray().put(JSONObject().put("type", "text").put("text", question))
                    .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", dataUrl(image))))
            } else question
            return JSONObject().put("model", model).put(
                "messages",
                JSONArray().put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
        }
        val json = try {
            post("$baseUrl/chat/completions", key, body(withImage = true))
        } catch (e: HttpError) {
            // Text-only models reject image parts with a 4xx; retry without the photo rather than fail.
            if (image == null || e.code !in 400..499) throw e
            post("$baseUrl/chat/completions", key, body(withImage = false))
        }
        return json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
    }

    // ---------- helpers ----------

    private class HttpError(val code: Int, msg: String) : IOException(msg)

    private fun post(url: String, key: String?, body: JSONObject): JSONObject {
        val req = Request.Builder().url(url)
            .apply { if (!key.isNullOrBlank()) header("Authorization", "Bearer $key") }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull()
                throw HttpError(r.code, "HTTP ${r.code}: ${msg?.takeIf { it.isNotBlank() } ?: text.take(200)}")
            }
            return JSONObject(text)
        }
    }

    private fun dataUrl(jpg: ByteArray) = "data:image/jpeg;base64," + Base64.encodeToString(jpg, Base64.NO_WRAP)

    /** Strip what TTS would read literally: markdown, links, bare URLs, citation markers. */
    fun speakable(s: String): String = s
        .replace(Regex("""\(\[([^\]]*)]\([^)]*\)\)"""), "") // "([site](url))" citation suffixes
        .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1") // [text](url) -> text
        .replace(Regex("""https?://\S+"""), "")
        .replace(Regex("""\[\d+(,\s*\d+)*]"""), "") // [1], [2, 3]
        .replace(Regex("""(?m)^\s{0,3}(#{1,6}\s+|[-*•]\s+|\d+\.\s+)"""), "")
        .replace(Regex("""[*_`]{1,3}"""), "")
        .replace(Regex("""[ \t]+([.,;:!?])"""), "$1")
        .replace(Regex("""[ \t]{2,}"""), " ")
        .trim()
}
