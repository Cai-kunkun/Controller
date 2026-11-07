package dev.arrbrants.controller.llm

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Remaining credit for the DeepSeek official API.
 * Endpoint: GET https://api.deepseek.com/user/balance
 */
object DeepSeekBalance {

    const val HOST = "api.deepseek.com"
    private const val ENDPOINT = "https://api.deepseek.com/user/balance"

    data class Balance(
        val currency: String,
        val total: String,
        val granted: String,
        val toppedUp: String,
        val available: Boolean
    ) {
        val display: String
            get() = when (currency.uppercase()) {
                "CNY" -> "¥$total"
                "USD" -> "$$total"
                else -> "$total $currency"
            }
    }

    /** True only for the official DeepSeek host (not a proxy that forwards it). */
    fun isOfficial(baseUrl: String): Boolean {
        if (baseUrl.isBlank()) return false
        return try {
            URL(LlmClient.endpoint(baseUrl)).host.equals(HOST, ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    fun fetch(baseUrl: String, apiKey: String): Balance? {
        if (apiKey.isBlank() || !isOfficial(baseUrl)) return null
        val conn = try {
            URL(ENDPOINT).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return null
        }
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parse(body)
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    fun parse(json: String): Balance? {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return null
        }
        val infos = root.optJSONArray("balance_infos") ?: return null
        if (infos.length() == 0) return null
        val info = infos.optJSONObject(0) ?: return null
        val total = info.optString("total_balance", "")
        if (total.isBlank()) return null
        return Balance(
            currency = info.optString("currency", "CNY"),
            total = total,
            granted = info.optString("granted_balance", ""),
            toppedUp = info.optString("topped_up_balance", ""),
            available = root.optBoolean("is_available", true)
        )
    }
}
