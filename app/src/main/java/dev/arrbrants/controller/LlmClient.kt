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
        val elapsedMs: Long,
        /** Time to the first streamed token; 0 for non-streaming requests. */
        val firstTokenMs: Long = 0,
        /** Time spent receiving tokens after the first one; 0 for non-streaming. */
        val decodeMs: Long = 0
    ) {
        val tokensPerSecond: Double
            get() = when {
                decodeMs > 0 -> completionTokens * 1000.0 / decodeMs
                elapsedMs > 0 -> completionTokens * 1000.0 / elapsedMs
                else -> 0.0
            }
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
        val started = System.currentTimeMillis()
        val conn = open(cfg, buildBody(cfg, msgs, stream = false))
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
     * Streaming variant of [send]. [onDelta] runs on the calling (worker)
     * thread for every content chunk, so the UI can render tokens as they
     * arrive. Usage is picked up from the final `include_usage` chunk when the
     * provider supports it; otherwise completion tokens are estimated.
     */
    fun stream(cfg: Config, msgs: List<Msg>, onDelta: (String) -> Unit): Reply {
        val started = System.currentTimeMillis()
        val conn = open(cfg, buildBody(cfg, msgs, stream = true))
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw IOException("HTTP $code ${err.take(300)}")
        }

        val sb = StringBuilder()
        var firstTokenAt = 0L
        var lastTokenAt = 0L
        var usage: Usage? = null
        conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                val trimmed = line.trim()
                if (!trimmed.startsWith("data:")) continue
                val data = trimmed.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isEmpty()) continue
                val chunk = try {
                    JSONObject(data)
                } catch (e: Exception) {
                    continue
                }
                if (chunk.has("usage") && !chunk.isNull("usage")) {
                    val u = parseUsage(chunk, 0)
                    if (u.promptTokens > 0 || u.completionTokens > 0) usage = u
                }
                val choices = chunk.optJSONArray("choices") ?: continue
                val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: continue
                if (delta.isNull("content")) continue
                val piece = delta.optString("content", "")
                if (piece.isEmpty()) continue
                val now = System.currentTimeMillis()
                if (firstTokenAt == 0L) firstTokenAt = now
                lastTokenAt = now
                sb.append(piece)
                onDelta(piece)
            }
        }

        val finished = System.currentTimeMillis()
        val text = sb.toString()
        return Reply(
            text.ifBlank { "(empty response)" },
            Usage(
                promptTokens = usage?.promptTokens ?: 0,
                completionTokens = usage?.completionTokens
                    ?: TokenBudget.text(text).toLong(),
                cacheHitTokens = usage?.cacheHitTokens ?: 0,
                cacheMissTokens = usage?.cacheMissTokens ?: 0,
                elapsedMs = finished - started,
                firstTokenMs = if (firstTokenAt > 0) firstTokenAt - started else 0,
                decodeMs = if (firstTokenAt > 0) lastTokenAt - firstTokenAt else 0
            )
        )
    }

    /** Builds the request body shared by the streaming and blocking paths. */
    private fun buildBody(cfg: Config, msgs: List<Msg>, stream: Boolean): JSONObject {
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
            .put("stream", stream)
            .put("messages", arr)
        if (stream) {
            // Ask OpenAI-compatible providers to report usage in the last chunk.
            body.put("stream_options", JSONObject().put("include_usage", true))
        }
        return body
    }

    private fun open(cfg: Config, body: JSONObject): HttpURLConnection {
        if (cfg.baseUrl.isBlank()) throw IOException("Provider URL is not set")
        if (cfg.model.isBlank()) throw IOException("Model is not set")

        val conn = URL(endpoint(cfg.baseUrl)).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 180000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        if (cfg.apiKey.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return conn
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
