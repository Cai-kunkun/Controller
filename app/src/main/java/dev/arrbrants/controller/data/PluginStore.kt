package dev.arrbrants.controller.data

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File

/** Loads, validates and persists declarative plugins under filesDir/plugins. */
object PluginStore {

    private const val DIR = "plugins"
    private const val FILE = "plugin.json"
    private const val MAX_IMPORT_BYTES = 64 * 1024
    private val ID = Regex("^[a-z0-9_]{1,32}$")
    private val UI_ACTIONS = setOf("tap", "tap_text", "text", "wait", "key")

    fun dir(context: Context): File = File(context.filesDir, DIR)

    fun loadAll(context: Context): List<Plugin> {
        val root = dir(context)
        if (!root.isDirectory) return emptyList()
        val out = ArrayList<Plugin>()
        for (child in root.listFiles().orEmpty()) {
            if (!child.isDirectory) continue
            val file = File(child, FILE)
            if (!file.isFile) continue
            val plugin = try {
                parse(file.readText())
            } catch (e: Exception) {
                null
            }
            if (plugin != null) out.add(plugin)
        }
        return out.sortedBy { it.name.lowercase() }
    }

    fun enabled(context: Context): List<Plugin> =
        loadAll(context).filter { it.enabled }

    fun importJson(context: Context, json: String): Plugin? {
        val plugin = parse(json) ?: return null
        save(context, plugin.copy(enabled = false))
        return plugin.copy(enabled = false)
    }

    fun importUri(context: Context, uri: Uri): Plugin? {
        val json = try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return null
            if (bytes.size > MAX_IMPORT_BYTES) return null
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            return null
        }
        return importJson(context, json)
    }

    fun setEnabled(context: Context, plugin: Plugin, enabled: Boolean) {
        save(context, plugin.copy(enabled = enabled))
    }

    fun delete(context: Context, id: String) {
        File(dir(context), id).deleteRecursively()
    }

    private fun save(context: Context, plugin: Plugin) {
        val folder = File(dir(context), plugin.id)
        folder.mkdirs()
        File(folder, FILE).writeText(plugin.toJson().toString())
    }

    /** Parses and validates a plugin file. Returns null when anything is off. */
    fun parse(json: String): Plugin? {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return null
        }
        val id = root.optString("id").trim()
        if (!ID.matches(id)) return null
        val name = root.optString("name").trim().ifBlank { id }.take(60)
        val description = root.optString("description").trim().take(300)
        val parameters = root.optJSONObject("parameters")
            ?: JSONObject().put("type", "object").put("properties", JSONObject())
        val stepsArray = root.optJSONArray("steps") ?: return null
        if (stepsArray.length() !in 1..20) return null

        val steps = ArrayList<PluginStep>(stepsArray.length())
        for (i in 0 until stepsArray.length()) {
            val step = stepsArray.optJSONObject(i) ?: return null
            steps.add(parseStep(step) ?: return null)
        }
        return Plugin(
            id = id,
            name = name,
            description = description,
            parameters = parameters.toString(),
            steps = steps,
            enabled = root.optBoolean("enabled", false)
        )
    }

    private fun parseStep(step: JSONObject): PluginStep? {
        return when (step.optString("type")) {
            "shell" -> {
                val command = step.optString("command").trim()
                if (command.isBlank() || command.length > 2000) null
                else PluginStep("shell", shell = command)
            }

            "http" -> {
                val method = step.optString("method", "GET").uppercase()
                val url = step.optString("url").trim()
                if (method !in setOf("GET", "POST")) return null
                val templated = url.contains("{{")
                if (!templated && !url.startsWith("http://") && !url.startsWith("https://")) {
                    return null
                }
                if (url.length > 2000) return null
                val body = step.optString("body").takeIf { it.isNotBlank() }?.take(8000)
                val headers = mutableMapOf<String, String>()
                step.optJSONObject("headers")?.let { obj ->
                    for (key in obj.keys()) {
                        headers[key] = obj.optString(key).take(200)
                    }
                }
                PluginStep("http", method = method, url = url, body = body, headers = headers)
            }

            "action" -> {
                val action = step.optString("action").trim().lowercase()
                if (action !in UI_ACTIONS) return null
                val x = step.optInt("x", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
                val y = step.optInt("y", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
                val text = step.optString("text").takeIf { it.isNotBlank() }
                val ms = step.optInt("ms", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
                val valid = when (action) {
                    "tap" -> x != null && y != null
                    "tap_text", "text", "key" -> text != null
                    "wait" -> ms != null && ms in 0..5000
                    else -> false
                }
                if (!valid) null
                else PluginStep(
                    "action", action = action, x = x, y = y, text = text, ms = ms
                )
            }

            else -> null
        }
    }
}
