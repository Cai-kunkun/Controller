package dev.arrbrants.controller.llm

import dev.arrbrants.controller.data.Plugin
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

    data class Config(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        /** Declare the device_action tool so the model can emit native tool calls. */
        val useTools: Boolean = true,
        /** DeepSeek `reasoning_effort`: "", none, low, high or max. */
        val reasoningEffort: String = "",
        /** Enabled declarative plugins, exposed as additional tools. */
        val plugins: List<Plugin> = emptyList()
    )

    /** A structured tool call returned by the provider. */
    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String
    )

    /** One outgoing message; non-empty [imageDataUrls] add image content parts. */
    data class Msg(
        val role: String,
        val text: String,
        val imageDataUrls: List<String> = emptyList(),
        /** Estimated prompt cost of the attached images, used for budgeting. */
        val imageTokens: Int = 0,
        /** Set on assistant messages that called a tool. */
        val toolCalls: List<ToolCall> = emptyList(),
        /** Set on role="tool" result messages. */
        val toolCallId: String? = null
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

    data class Reply(
        val text: String,
        val usage: Usage,
        val toolCalls: List<ToolCall> = emptyList(),
        /** DeepSeek `reasoning_content` (chain of thought), when present. */
        val reasoning: String = ""
    )

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
        val calls = message?.optJSONArray("tool_calls")?.let { parseToolCalls(it) }
            ?: emptyList()
        val reasoning = message?.let {
            if (it.isNull("reasoning_content")) "" else it.optString("reasoning_content", "")
        }.orEmpty()
        val replyText = content.ifBlank {
            if (calls.isEmpty()) "(empty response)" else ""
        }
        return Reply(replyText, parseUsage(root, elapsed), calls, reasoning)
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
        val reasoning = StringBuilder()
        val callAccs = LinkedHashMap<Int, ToolCallAcc>()
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

                // DeepSeek streams the chain of thought separately.
                if (!delta.isNull("reasoning_content")) {
                    val piece = delta.optString("reasoning_content", "")
                    if (piece.isNotEmpty()) {
                        val now = System.currentTimeMillis()
                        if (firstTokenAt == 0L) firstTokenAt = now
                        lastTokenAt = now
                        reasoning.append(piece)
                    }
                }

                // Native tool calls stream as fragments indexed by call.
                val toolDeltas = delta.optJSONArray("tool_calls")
                if (toolDeltas != null) {
                    for (i in 0 until toolDeltas.length()) {
                        val part = toolDeltas.optJSONObject(i) ?: continue
                        val index = part.optInt("index", i)
                        val acc = callAccs.getOrPut(index) { ToolCallAcc() }
                        if (part.has("id")) acc.id = part.optString("id")
                        val fn = part.optJSONObject("function")
                        if (fn != null) {
                            if (fn.has("name")) acc.name = fn.optString("name")
                            if (fn.has("arguments")) acc.args.append(fn.optString("arguments"))
                        }
                    }
                    val now = System.currentTimeMillis()
                    if (firstTokenAt == 0L) firstTokenAt = now
                    lastTokenAt = now
                }

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
        val calls = callAccs.entries.sortedBy { it.key }.map { (index, acc) ->
            LlmClient.ToolCall(
                acc.id.ifBlank { "call_$index" },
                acc.name,
                acc.args.toString()
            )
        }
        return Reply(
            text.ifBlank { if (calls.isEmpty()) "(empty response)" else "" },
            Usage(
                promptTokens = usage?.promptTokens ?: 0,
                completionTokens = usage?.completionTokens
                    ?: TokenBudget.text(text).toLong(),
                cacheHitTokens = usage?.cacheHitTokens ?: 0,
                cacheMissTokens = usage?.cacheMissTokens ?: 0,
                elapsedMs = finished - started,
                firstTokenMs = if (firstTokenAt > 0) firstTokenAt - started else 0,
                decodeMs = if (firstTokenAt > 0) lastTokenAt - firstTokenAt else 0
            ),
            calls,
            reasoning.toString()
        )
    }

    private class ToolCallAcc(
        var id: String = "",
        var name: String = "",
        val args: StringBuilder = StringBuilder()
    )

    private fun parseToolCalls(arr: JSONArray): List<ToolCall> {
        val out = ArrayList<ToolCall>()
        for (i in 0 until arr.length()) {
            val call = arr.optJSONObject(i) ?: continue
            val fn = call.optJSONObject("function") ?: continue
            out.add(
                ToolCall(
                    call.optString("id", "call_$i"),
                    fn.optString("name", ""),
                    fn.opt("arguments")?.toString().orEmpty()
                )
            )
        }
        return out
    }

    /** Builds the request body shared by the streaming and blocking paths. */
    private fun buildBody(cfg: Config, msgs: List<Msg>, stream: Boolean): JSONObject {
        val arr = JSONArray()
        for (m in msgs) {
            arr.put(messageJson(m))
        }
        val body = JSONObject()
            .put("model", cfg.model)
            .put("stream", stream)
            .put("messages", arr)
        if (cfg.useTools) {
            body.put("tools", toolsSchema(cfg))
            body.put("tool_choice", "auto")
        }
        if (cfg.reasoningEffort.isNotBlank()) {
            body.put("reasoning_effort", cfg.reasoningEffort)
        }
        if (stream) {
            // Ask OpenAI-compatible providers to report usage in the last chunk.
            body.put("stream_options", JSONObject().put("include_usage", true))
        }
        return body
    }

    private fun messageJson(m: Msg): JSONObject {
        if (m.role == "tool") {
            return JSONObject()
                .put("role", "tool")
                .put("tool_call_id", m.toolCallId ?: "")
                .put("content", m.text)
        }
        if (m.toolCalls.isNotEmpty()) {
            val calls = JSONArray()
            for (c in m.toolCalls) {
                calls.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject().put("name", c.name).put("arguments", c.arguments)
                        )
                )
            }
            return JSONObject()
                .put("role", "assistant")
                .put("content", if (m.text.isBlank()) JSONObject.NULL else m.text)
                .put("tool_calls", calls)
        }
        val content: Any = if (m.imageDataUrls.isEmpty()) {
            m.text
        } else {
            JSONArray()
                .put(JSONObject().put("type", "text").put("text", m.text))
                .apply {
                    for (url in m.imageDataUrls) {
                        put(
                            JSONObject()
                                .put("type", "image_url")
                                .put("image_url", JSONObject().put("url", url))
                        )
                    }
                }
        }
        return JSONObject().put("role", m.role).put("content", content)
    }

    /** Tool list: the built-in device action plus every enabled plugin. */
    private fun toolsSchema(cfg: Config): JSONArray {
        val tools = JSONArray().put(deviceActionTool())
        for (plugin in cfg.plugins) tools.put(plugin.toolJson())
        return tools
    }

    /** The single built-in tool the model may call to operate the phone. */
    private fun deviceActionTool(): JSONObject = JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", "device_action")
                    .put(
                        "description",
                        "Operate the Android phone: exactly one UI action per call. " +
                            "Use action=look when you need to see the current screen first."
                    )
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject()
                                    .put("thought", stringProp("Why you do this"))
                                    .put(
                                        "action",
                                        JSONObject()
                                            .put("type", "string")
                                            .put(
                                                "enum",
                                                JSONArray(
                                                    listOf(
                                                        "look", "tap", "double_tap", "long_press",
                                                        "tap_text", "swipe", "text", "key", "wait",
                                                        "done", "ask"
                                                    )
                                                )
                                            )
                                            .put("description", "The action to perform")
                                    )
                                    .put("x", intProp("X coordinate, 0-1000"))
                                    .put("y", intProp("Y coordinate, 0-1000"))
                                    .put("x1", intProp("Swipe start X, 0-1000"))
                                    .put("y1", intProp("Swipe start Y, 0-1000"))
                                    .put("x2", intProp("Swipe end X, 0-1000"))
                                    .put("y2", intProp("Swipe end Y, 0-1000"))
                                    .put("duration", intProp("Swipe duration in ms"))
                                    .put("ms", intProp("Wait duration in ms"))
                                    .put("text", stringProp("Text to tap by label, or text to type"))
                                    .put("key", stringProp("back | home | enter | recents"))
                                    .put("summary", stringProp("What was accomplished (done)"))
                                    .put("question", stringProp("Question for the user (ask)"))
                                    .put("notes", stringProp("Scratchpad carried across steps"))
                            )
                            .put("required", JSONArray(listOf("thought", "action")))
                    )
            )

    private fun stringProp(description: String): JSONObject =
        JSONObject().put("type", "string").put("description", description)

    private fun intProp(description: String): JSONObject =
        JSONObject().put("type", "integer").put("description", description)

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
