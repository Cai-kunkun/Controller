package dev.arrbrants.controller.data

import dev.arrbrants.controller.llm.LlmClient
import android.content.SharedPreferences
import org.json.JSONObject

/** Aggregated cache/perf counters for one conversation. */
data class UsageStats(
    val requests: Int = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cacheHit: Long = 0,
    val cacheMiss: Long = 0,
    val lastPromptTokens: Long = 0,
    val lastCompletionTokens: Long = 0,
    val lastTps: Double = 0.0,
    val elapsedMs: Long = 0,
    val compactions: Int = 0
) {
    val cacheTotal: Long get() = cacheHit + cacheMiss

    val hitRate: Double
        get() = if (cacheTotal <= 0) 0.0 else cacheHit.toDouble() / cacheTotal

    fun plus(usage: LlmClient.Usage): UsageStats = copy(
        requests = requests + 1,
        promptTokens = promptTokens + usage.promptTokens,
        completionTokens = completionTokens + usage.completionTokens,
        cacheHit = cacheHit + usage.cacheHitTokens,
        cacheMiss = cacheMiss + usage.cacheMissTokens,
        lastPromptTokens = usage.promptTokens,
        lastCompletionTokens = usage.completionTokens,
        lastTps = usage.tokensPerSecond,
        elapsedMs = elapsedMs + usage.elapsedMs
    )

    fun withCompaction(): UsageStats = copy(compactions = compactions + 1)

    fun toJson(): JSONObject = JSONObject()
        .put("requests", requests)
        .put("prompt", promptTokens)
        .put("completion", completionTokens)
        .put("hit", cacheHit)
        .put("miss", cacheMiss)
        .put("lastPrompt", lastPromptTokens)
        .put("lastCompletion", lastCompletionTokens)
        .put("lastTps", lastTps)
        .put("elapsed", elapsedMs)
        .put("compactions", compactions)

    companion object {
        fun fromJson(o: JSONObject?): UsageStats {
            if (o == null) return UsageStats()
            return UsageStats(
                requests = o.optInt("requests", 0),
                promptTokens = o.optLong("prompt", 0),
                completionTokens = o.optLong("completion", 0),
                cacheHit = o.optLong("hit", 0),
                cacheMiss = o.optLong("miss", 0),
                lastPromptTokens = o.optLong("lastPrompt", 0),
                lastCompletionTokens = o.optLong("lastCompletion", 0),
                lastTps = o.optDouble("lastTps", 0.0),
                elapsedMs = o.optLong("elapsed", 0),
                compactions = o.optInt("compactions", 0)
            )
        }
    }
}

/**
 * Persists [UsageStats] per conversation in SharedPreferences. The agent
 * service writes from its worker thread while the UI reads on the main thread,
 * so every access is serialized on [LOCK].
 */
object StatsStore {

    private const val KEY = "usage_stats"
    private val LOCK = Any()

    fun load(prefs: SharedPreferences, conversationId: Long): UsageStats =
        synchronized(LOCK) { read(prefs, conversationId) }

    fun record(
        prefs: SharedPreferences,
        conversationId: Long,
        usage: LlmClient.Usage
    ): UsageStats = synchronized(LOCK) {
        val stats = read(prefs, conversationId).plus(usage)
        write(prefs, conversationId, stats)
        stats
    }

    fun bumpCompaction(prefs: SharedPreferences, conversationId: Long): UsageStats =
        synchronized(LOCK) {
            val stats = read(prefs, conversationId).withCompaction()
            write(prefs, conversationId, stats)
            stats
        }

    fun remove(prefs: SharedPreferences, conversationId: Long) = synchronized(LOCK) {
        val all = readAll(prefs)
        all.remove(conversationId.toString())
        prefs.edit().putString(KEY, all.toString()).apply()
    }

    private fun read(prefs: SharedPreferences, conversationId: Long): UsageStats =
        UsageStats.fromJson(readAll(prefs).optJSONObject(conversationId.toString()))

    private fun write(prefs: SharedPreferences, conversationId: Long, stats: UsageStats) {
        val all = readAll(prefs)
        all.put(conversationId.toString(), stats.toJson())
        prefs.edit().putString(KEY, all.toString()).apply()
    }

    private fun readAll(prefs: SharedPreferences): JSONObject = try {
        JSONObject(prefs.getString(KEY, null) ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }
}
