package dev.arrbrants.controller.llm

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Fetches the provider's model list with the configured API key. */
object ModelCatalog {

    fun list(cfg: LlmClient.Config): List<String> {
        if (cfg.baseUrl.isBlank() || cfg.apiKey.isBlank()) return emptyList()
        val base = cfg.baseUrl.trim().trimEnd('/')
        val root = base.removeSuffix("/chat/completions").removeSuffix("/v1")
        val urls = listOf("$root/models", "$root/v1/models").distinct()
        for (url in urls) {
            val models = fetch(url, cfg.apiKey)
            if (models.isNotEmpty()) return models
        }
        return emptyList()
    }

    private fun fetch(url: String, apiKey: String): List<String> {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return emptyList()
        }
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode !in 200..299) return emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parse(body)
        } catch (e: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    fun parse(json: String): List<String> {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return emptyList()
        }
        val data = root.optJSONArray("data") ?: return emptyList()
        val models = LinkedHashSet<String>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val id = item.optString("id").trim()
            if (id.isNotEmpty()) models.add(id)
        }
        return models.toList()
    }
}
