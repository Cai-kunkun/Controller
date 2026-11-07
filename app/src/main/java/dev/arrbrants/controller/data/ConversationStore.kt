package dev.arrbrants.controller.data

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

    /** Appends one message atomically, creating the conversation if needed. */
    fun append(
        prefs: SharedPreferences,
        conversationId: Long,
        message: ChatMessage,
        defaultTitle: String
    ) = synchronized(LOCK) {
        val all = doLoad(prefs)
        val conversation = all.find { it.id == conversationId }
        if (conversation == null) {
            all.add(0, Conversation(conversationId, defaultTitle, mutableListOf(message)))
        } else {
            conversation.messages.add(message)
        }
        doSave(prefs, all)
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
                    val attachments = mutableListOf<Attachment>()
                    val attArr = m.optJSONArray("attachments")
                    if (attArr != null) {
                        for (k in 0 until attArr.length()) {
                            val a = attArr.getJSONObject(k)
                            attachments.add(
                                Attachment(
                                    a.getString("path"),
                                    a.getString("name"),
                                    a.optString("mime", "application/octet-stream"),
                                    a.optLong("size", 0)
                                )
                            )
                        }
                    }
                    msgs.add(
                        ChatMessage(
                            m.getString("role"),
                            m.getString("content"),
                            if (m.has("imagePath")) m.getString("imagePath") else null,
                            attachments,
                            if (m.has("rawContent")) m.getString("rawContent") else null,
                            if (m.has("reasoning")) m.getString("reasoning") else null
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
                if (m.rawContent != null) msg.put("rawContent", m.rawContent)
                if (m.reasoning != null) msg.put("reasoning", m.reasoning)
                if (m.attachments.isNotEmpty()) {
                    val attArr = JSONArray()
                    for (a in m.attachments) {
                        attArr.put(
                            JSONObject()
                                .put("path", a.path)
                                .put("name", a.name)
                                .put("mime", a.mime)
                                .put("size", a.size)
                        )
                    }
                    msg.put("attachments", attArr)
                }
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
