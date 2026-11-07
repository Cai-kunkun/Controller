package dev.arrbrants.controller.agent

import android.content.Context
import dev.arrbrants.controller.data.Plugin
import dev.arrbrants.controller.data.PluginStep
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Runs one declarative plugin on the agent worker thread: shell, HTTP and
 * built-in UI actions. Results are capped before they go back to the model.
 */
class PluginExecutor(
    private val context: Context,
    private val actions: ActionExecutor?,
    private val onProgress: (String) -> Unit = {}
) {

    fun run(plugin: Plugin, args: JSONObject): String {
        val output = StringBuilder()
        var last = ""
        for ((index, step) in plugin.steps.withIndex()) {
            onProgress("${plugin.name}: ${index + 1}/${plugin.steps.size} ${step.type}")
            val result = try {
                when (step.type) {
                    "shell" -> shell(fill(step.shell.orEmpty(), args, last))
                    "http" -> http(step, args, last)
                    "action" -> action(step, args, last)
                    else -> "$ERROR unsupported step"
                }
            } catch (e: Exception) {
                "$ERROR ${e.message ?: "step failed"}"
            }
            if (result.startsWith(ERROR)) {
                return "step ${index + 1} failed: ${result.removePrefix(ERROR)}".take(2000)
            }
            last = result
            output.append(result.take(2000)).append('\n')
        }
        return output.toString().trim().take(6000).ifBlank { "ok" }
    }

    private fun shell(command: String): String {
        val result = RootShell.exec(command, 20_000)
        val out = result.stdout.trim()
        val err = result.stderr.trim()
        if (!result.ok) {
            return "$ERROR ${err.ifBlank { out }.ifBlank { "exit ${result.code}" }}"
        }
        return out.ifBlank { err }.take(4000)
    }

    private fun http(step: PluginStep, args: JSONObject, last: String): String {
        val url = fill(step.url.orEmpty(), args, last)
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = step.method ?: "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            for ((key, value) in step.headers) {
                conn.setRequestProperty(key, fill(value, args, last))
            }
            if (conn.requestMethod == "POST") {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                for ((key, value) in step.headers) {
                    conn.setRequestProperty(key, fill(value, args, last))
                }
                conn.outputStream.use {
                    it.write(fill(step.body.orEmpty(), args, last).toByteArray())
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) body.take(4000)
            else "$ERROR HTTP $code ${body.take(500)}"
        } finally {
            conn.disconnect()
        }
    }

    private fun action(step: PluginStep, args: JSONObject, last: String): String {
        val executor = actions ?: return "$ERROR UI actions unavailable"
        val action = when (step.action) {
            "tap" -> AgentProtocol.Action.Tap(step.x ?: 0, step.y ?: 0)
            "tap_text" -> AgentProtocol.Action.TapText(fill(step.text.orEmpty(), args, last))
            "text" -> AgentProtocol.Action.Text(fill(step.text.orEmpty(), args, last))
            "wait" -> AgentProtocol.Action.Wait(step.ms ?: 1000)
            "key" -> AgentProtocol.Action.Key(fill(step.text.orEmpty(), args, last))
            else -> return "$ERROR unknown action"
        }
        val error = executor.execute(action)
        return error?.let { "$ERROR $it" } ?: "ok"
    }

    private fun fill(template: String, args: JSONObject, last: String): String {
        var out = template.replace("{{result}}", last)
        for (key in args.keys()) {
            out = out.replace("{{$key}}", args.optString(key))
        }
        return out
    }

    private companion object {
        const val ERROR = "ERROR:"
    }
}
