package dev.arrbrants.controller.data

import org.json.JSONArray
import org.json.JSONObject

/** One built-in step a declarative plugin can run. */
data class PluginStep(
    val type: String,
    val shell: String? = null,
    val method: String? = null,
    val url: String? = null,
    val body: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val action: String? = null,
    val x: Int? = null,
    val y: Int? = null,
    val text: String? = null,
    val ms: Int? = null
) {
    fun toJson(): JSONObject = JSONObject().put("type", type).apply {
        shell?.let { put("command", it) }
        method?.let { put("method", it) }
        url?.let { put("url", it) }
        body?.let { put("body", it) }
        if (headers.isNotEmpty()) {
            put("headers", JSONObject().apply { headers.forEach { (k, v) -> put(k, v) } })
        }
        action?.let { put("action", it) }
        x?.let { put("x", it) }
        y?.let { put("y", it) }
        text?.let { put("text", it) }
        ms?.let { put("ms", it) }
    }
}

/**
 * A declarative plugin: a JSON-described tool composed of whitelisted steps.
 * Disabled until the user explicitly enables it.
 */
data class Plugin(
    val id: String,
    val name: String,
    val description: String,
    /** JSON Schema object as a string, passed straight to the model. */
    val parameters: String,
    val steps: List<PluginStep>,
    val enabled: Boolean = false
) {
    val toolName: String get() = "plugin_$id"

    fun toolJson(): JSONObject {
        val schema = try {
            JSONObject(parameters)
        } catch (e: Exception) {
            JSONObject().put("type", "object").put("properties", JSONObject())
        }
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", toolName)
                    .put("description", description.ifBlank { name })
                    .put("parameters", schema)
            )
    }

    fun toJson(): JSONObject {
        val stepsJson = JSONArray()
        for (step in steps) stepsJson.put(step.toJson())
        val schema = try {
            JSONObject(parameters)
        } catch (e: Exception) {
            JSONObject().put("type", "object").put("properties", JSONObject())
        }
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("description", description)
            .put("parameters", schema)
            .put("steps", stepsJson)
            .put("enabled", enabled)
    }
}
