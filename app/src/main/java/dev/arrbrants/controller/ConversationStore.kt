package dev.arrbrants.controller

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists chat conversations as a JSON blob in SharedPreferences.
 * Keeps things lightweight: no database, no extra dependencies.
 *
 * Access is guarded by [LOCK] because the agent foreground service appends
 * step messages while the UI may be saving state at the same time.
 */
object ConversationStore {

    data class Conversation(
        val id: Long,
        val title: String,
        val messages: MutableList<ChatMessage>
    )

    private const val KEY_CONVERSATIONS = "conversations_json"
    private val LOCK = Any()

    fun loadAll(prefs: SharedPreferences): MutableList<Conversation> =
        synchronized(LOCK) { doLoad(prefs) }

    fun saveAll(prefs: SharedPreferences, conversations: List<Conversation>) {
        synchronized(LOCK) { doSave(prefs, conversations) }
    }

    private fun doLoad(prefs: SharedPreferences): MutableList<Conversation> {
        val result = mutableListOf<Conversation>()
        val raw = prefs.getString(KEY_CONVERSATIONS, null) ?: return result
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val msgs = mutableListOf<ChatMessage>()
                val msgArr = obj.getJSONArray("messages")
                for (j in 0 until msgArr.length()) {
                    val m = msgArr.getJSONObject(j)
                    msgs.add(
                        ChatMessage(
                            m.getString("role"),
                            m.getString("content"),
                            if (m.has("imagePath")) m.getString("imagePath") else null
                        )
                    )
                }
                result.add(Conversation(obj.getLong("id"), obj.getString("title"), msgs))
            }
        } catch (e: Exception) {
            // Corrupted store: start fresh rather than crash.
        }
        return result
    }

    private fun doSave(prefs: SharedPreferences, conversations: List<Conversation>) {
        val arr = JSONArray()
        for (c in conversations) {
            val msgArr = JSONArray()
            for (m in c.messages) {
                val msg = JSONObject().put("role", m.role).put("content", m.content)
                if (m.imagePath != null) msg.put("imagePath", m.imagePath)
                msgArr.put(msg)
            }
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("title", c.title)
                    .put("messages", msgArr)
            )
        }
        prefs.edit().putString(KEY_CONVERSATIONS, arr.toString()).apply()
    }
}
