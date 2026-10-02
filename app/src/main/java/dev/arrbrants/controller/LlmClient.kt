package dev.arrbrants.controller

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal client for any OpenAI-compatible chat completions endpoint.
 * Works with OpenAI, Groq, OpenRouter, Together, Ollama, LM Studio, etc.
 * Messages may carry one image (data URL) for multimodal models.
 */
object LlmClient {

    data class Config(val baseUrl: String, val apiKey: String, val model: String)

    /** One outgoing message; a non-null [imageDataUrl] adds an image content part. */
    data class Msg(val role: String, val text: String, val imageDataUrl: String? = null)

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

    fun send(cfg: Config, msgs: List<Msg>): String {
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
        if (code !in 200..299) {
            throw IOException("HTTP $code ${text.take(300)}")
        }

        val choice = JSONObject(text).getJSONArray("choices").getJSONObject(0)
        val message = choice.optJSONObject("message")
        val content = when (val c = message?.opt("content")) {
            is String -> c
            null -> choice.optString("text", "")
            else -> c.toString()
        }
        return content.ifBlank { "(empty response)" }
    }
}
