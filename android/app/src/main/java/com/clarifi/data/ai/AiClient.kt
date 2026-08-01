package com.clarifi.data.ai

import com.clarifi.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

/** An image ready to send to a vision model. */
data class VisionImage(val mimeType: String, val base64: String)

/**
 * Talks to Groq, Gemini or Claude.
 *
 * The request bodies mirror `_llm_complete_*` in app.py field for field, so both
 * apps get the same answers from the same key. Deliberately built on
 * `HttpURLConnection` and `org.json`: four endpoints and one response shape do
 * not justify pulling in an HTTP client and a serialisation library.
 */
class AiClient {

    private val userAgent = "ClariFi/${BuildConfig.VERSION_NAME}"

    /**
     * Sends a prompt (optionally with images) and returns the model's raw text.
     * Always runs off the main thread.
     */
    suspend fun complete(
        prompt: String,
        apiKey: String,
        maxTokens: Int = 500,
        timeoutSeconds: Int = 30,
        images: List<VisionImage> = emptyList(),
    ): String = withContext(Dispatchers.IO) {
        when (AiProvider.detect(apiKey)) {
            AiProvider.GROQ -> completeGroq(prompt, apiKey, maxTokens, timeoutSeconds, images)
            AiProvider.CLAUDE -> completeClaude(prompt, apiKey, maxTokens, timeoutSeconds, images)
            AiProvider.GEMINI -> completeGemini(prompt, apiKey, maxTokens, timeoutSeconds, images)
        }
    }

    /**
     * Checks that a key is accepted, by asking for a one-token reply.
     *
     * Mirrors `_verify_ai_key`: cheaper and clearer than letting the user find out
     * their key is wrong when a receipt fails to scan.
     */
    suspend fun verifyKey(apiKey: String): AiProvider {
        val provider = AiProvider.detect(apiKey)
        complete(prompt = "Reply with the single word: ok", apiKey = apiKey, maxTokens = 5, timeoutSeconds = 20)
        return provider
    }

    // ── providers ─────────────────────────────────────────────────────────────

    private fun completeGemini(
        prompt: String,
        apiKey: String,
        maxTokens: Int,
        timeoutSeconds: Int,
        images: List<VisionImage>,
    ): String {
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        images.forEach { image ->
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", image.mimeType).put("data", image.base64),
                )
            )
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put(
                "generationConfig",
                JSONObject()
                    .put("maxOutputTokens", maxTokens)
                    .put("responseMimeType", "application/json"),
            )

        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            AiProvider.GEMINI.model + ":generateContent?key=" + URLEncoder.encode(apiKey, "UTF-8")

        val response = post(url, body.toString(), timeoutSeconds) { it }
        val candidates = response.optJSONArray("candidates") ?: JSONArray()
        if (candidates.length() == 0) return ""
        val responseParts = candidates.getJSONObject(0)
            .optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        return buildString {
            for (index in 0 until responseParts.length()) {
                append(responseParts.optJSONObject(index)?.optString("text").orEmpty())
            }
        }
    }

    private fun completeGroq(
        prompt: String,
        apiKey: String,
        maxTokens: Int,
        timeoutSeconds: Int,
        images: List<VisionImage>,
    ): String {
        val content: Any = if (images.isEmpty()) {
            prompt
        } else {
            JSONArray().apply {
                put(JSONObject().put("type", "text").put("text", prompt))
                images.forEach { image ->
                    put(
                        JSONObject()
                            .put("type", "image_url")
                            .put(
                                "image_url",
                                JSONObject().put("url", "data:${image.mimeType};base64,${image.base64}"),
                            )
                    )
                }
            }
        }

        val body = JSONObject()
            .put("model", if (images.isEmpty()) AiProvider.GROQ.model else AiProvider.GROQ.visionModel)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("max_tokens", maxTokens)
            .put("temperature", 0)
            // qwen3.6 is a reasoning model: left on, it emits a <think> block whose
            // braces break JSON extraction and burns the whole token budget.
            .put("reasoning_effort", "none")
            .put("response_format", JSONObject().put("type", "json_object"))

        val response = post(
            url = "https://api.groq.com/openai/v1/chat/completions",
            body = body.toString(),
            timeoutSeconds = timeoutSeconds,
            headers = mapOf("authorization" to "Bearer $apiKey"),
        ) { it }

        val choices = response.optJSONArray("choices") ?: JSONArray()
        if (choices.length() == 0) return ""
        return choices.getJSONObject(0).optJSONObject("message")?.optString("content").orEmpty()
    }

    private fun completeClaude(
        prompt: String,
        apiKey: String,
        maxTokens: Int,
        timeoutSeconds: Int,
        images: List<VisionImage>,
    ): String {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
        images.forEach { image ->
            content.put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", image.mimeType)
                            .put("data", image.base64),
                    )
            )
        }

        val body = JSONObject()
            .put("model", AiProvider.CLAUDE.model)
            .put("max_tokens", maxTokens)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))

        val response = post(
            url = "https://api.anthropic.com/v1/messages",
            body = body.toString(),
            timeoutSeconds = timeoutSeconds,
            headers = mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"),
        ) { it }

        val blocks = response.optJSONArray("content") ?: JSONArray()
        return buildString {
            for (index in 0 until blocks.length()) {
                val block = blocks.optJSONObject(index) ?: continue
                if (block.optString("type") == "text") append(block.optString("text"))
            }
        }
    }

    // ── transport ─────────────────────────────────────────────────────────────

    private fun post(
        url: String,
        body: String,
        timeoutSeconds: Int,
        headers: Map<String, String> = emptyMap(),
        transform: (JSONObject) -> JSONObject,
    ): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = timeoutSeconds * 1000
            readTimeout = timeoutSeconds * 1000
            setRequestProperty("content-type", "application/json")
            setRequestProperty("user-agent", userAgent)
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }

        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw AiException(
                    message = "AI request failed (HTTP $status)." + describeError(detail),
                    code = "ai_error",
                )
            }

            val text = connection.inputStream.bufferedReader().use { it.readText() }
            return transform(JSONObject(text))
        } catch (e: AiException) {
            throw e
        } catch (e: UnknownHostException) {
            throw AiException(
                "Could not reach the AI service. Check your connection and try again.",
                code = "ai_unreachable",
            )
        } catch (e: IOException) {
            throw AiException(
                "Could not reach the AI service. Check your connection and try again.",
                code = "ai_unreachable",
            )
        } finally {
            connection.disconnect()
        }
    }

    /** Pulls the human-readable part out of a provider's error body, like `_ai_http_detail`. */
    private fun describeError(body: String): String {
        if (body.isBlank()) return ""
        val message = runCatching {
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message")
                ?: json.optString("message").takeIf { it.isNotBlank() }
        }.getOrNull()
        return message?.let { " ($it)" } ?: ""
    }
}
