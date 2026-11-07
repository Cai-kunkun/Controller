package dev.arrbrants.controller.data

import dev.arrbrants.controller.llm.LlmClient
import android.content.Context
import android.content.SharedPreferences

/**
 * Single source of truth for preference keys and typed accessors.
 * Activities and the foreground service should not depend on each other's
 * constants for this.
 */
object AppPrefs {

    const val FILE = "llm_prefs"

    const val KEY_URL = "base_url"
    const val KEY_API = "api_key"
    const val KEY_MODEL = "model"
    const val KEY_CONTEXT_WINDOW = "context_window"
    const val KEY_CURRENT = "current_id"
    const val KEY_STATS_COLLAPSED = "stats_collapsed"
    const val KEY_NATIVE_TOOLS = "native_tools"

    const val DEFAULT_CONTEXT_WINDOW = 128_000
    const val MIN_CONTEXT_WINDOW = 8_192

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Provider config assembled from prefs + the encrypted API key. */
    fun config(context: Context): LlmClient.Config = LlmClient.Config(
        get(context).getString(KEY_URL, "").orEmpty(),
        SecurePrefs.apiKey(context),
        get(context).getString(KEY_MODEL, "").orEmpty(),
        nativeTools(context)
    )

    fun contextWindow(context: Context): Int =
        get(context).getInt(KEY_CONTEXT_WINDOW, DEFAULT_CONTEXT_WINDOW)
            .coerceAtLeast(MIN_CONTEXT_WINDOW)

    fun nativeTools(context: Context): Boolean =
        get(context).getBoolean(KEY_NATIVE_TOOLS, true)

    fun statsCollapsed(context: Context): Boolean =
        get(context).getBoolean(KEY_STATS_COLLAPSED, false)

    fun currentConversation(context: Context): Long =
        get(context).getLong(KEY_CURRENT, -1L)

    fun setCurrentConversation(context: Context, id: Long) {
        get(context).edit().putLong(KEY_CURRENT, id).apply()
    }
}
