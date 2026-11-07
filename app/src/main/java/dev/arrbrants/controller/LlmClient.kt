package dev.arrbrants.controller

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal client for any OpenAI-compatible chat completions endpoint.
 * Works with OpenAI, Groq, OpenRouter, Together, Ollama, LM Studio, DeepSeek, etc.
 * Messages may carry one image (data URL) for multimodal models.
 */
object LlmClient {

    data class Config(val baseUrl: String, val apiKey: String, val model: String)

    /** One outgoing message; a non-null [imageDataUrl] adds an image content part. */
    data class Msg(
        val role: String,
        val text: String,
        val imageDataUrl: String? = null,
        /** Estimated prompt cost of the attached image, used for budgeting. */
        val imageTokens: Int = 0
    ) {
        val approxTokens: Int get() = TokenBudget.text(text) + imageTokens
    }

    /** Token accounting reported by the provider, plus client-side timing. */
    data class Usage(
        val promptTokens: Long,
        val completionTokens: Long,
        val cacheHitTokens: Long,
        val cacheMissTokens: Long,
        val elapsedMs: Long
    ) {
        val tokensPerSecond: Double
            get() = if (elapsedMs <= 0) 0.0 else completionTokens * 1000.0 / elapsedMs
    }

    data class Reply(val text: String, val usage: Usage)

    /** Normalize a user-provided base URL into a full chat/completions endpoint. */
    fun endpoint(baseUrl: String): String {
        val b = baseUrl.trim().trimEnd('/')
        return when {
            b.isEmpty() -> b
            b.endsWith("/chat/completions") -> b
            b.endsWith("/v1") -> "$b/chat/completions"
            else -> "$b/v1/chat/completions"
        }
    }

    fun send(cfg: Config, msgs: List<Msg>): Reply {
        if (cfg.baseUrl.isBlank()) throw IOException("Provider URL is not set")
        if (cfg.model.isBlank()) throw IOException("Model is not set")

        val started = System.currentTimeMillis()
        val conn = URL(endpoint(cfg.baseUrl)).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 180000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        if (cfg.apiKey.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
        }

        val arr = JSONArray()
        for (m in msgs) {
            val content: Any = if (m.imageDataUrl == null) {
                m.text
            } else {
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", m.text))
                    .put(
                        JSONObject()
                            .put("type", "image_url")
                            .put("image_url", JSONObject().put("url", m.imageDataUrl))
                    )
            }
            arr.put(JSONObject().put("role", m.role).put("content", content))
        }
        val body = JSONObject()
            .put("model", cfg.model)
            .put("stream", false)
            .put("messages", arr)

        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        val elapsed = System.currentTimeMillis() - started
        if (code !in 200..299) {
            throw IOException("HTTP $code ${text.take(300)}")
        }

        val root = JSONObject(text)
        val choice = root.getJSONArray("choices").getJSONObject(0)
        val message = choice.optJSONObject("message")
        val content = when (val c = message?.opt("content")) {
            is String -> c
            null -> choice.optString("text", "")
            else -> c.toString()
        }
        return Reply(content.ifBlank { "(empty response)" }, parseUsage(root, elapsed))
    }

    /**
     * Reads the cache accounting exposed by the common providers:
     * DeepSeek (`prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`),
     * OpenAI (`prompt_tokens_details.cached_tokens`) and
     * Anthropic-style (`cache_read_input_tokens`).
     */
    private fun parseUsage(root: JSONObject, elapsedMs: Long): Usage {
        val u = root.optJSONObject("usage") ?: return Usage(0, 0, 0, 0, elapsedMs)
        val prompt = u.longOr("prompt_tokens", u.longOr("input_tokens", 0))
        val completion = u.longOr("completion_tokens", u.longOr("output_tokens", 0))
        val details = u.optJSONObject("prompt_tokens_details")
        val hit = when {
            u.has("prompt_cache_hit_tokens") -> u.longOr("prompt_cache_hit_tokens", 0)
            u.has("cache_read_input_tokens") -> u.longOr("cache_read_input_tokens", 0)
            details != null && details.has("cached_tokens") ->
                details.longOr("cached_tokens", 0)
            else -> 0L
        }.coerceIn(0, prompt.coerceAtLeast(0))
        val miss = when {
            u.has("prompt_cache_miss_tokens") -> u.longOr("prompt_cache_miss_tokens", 0)
            else -> (prompt - hit).coerceAtLeast(0)
        }
        return Usage(prompt, completion, hit, miss, elapsedMs)
    }

    private fun JSONObject.longOr(key: String, default: Long): Long =
        if (has(key) && !isNull(key)) optLong(key, default) else default
}
