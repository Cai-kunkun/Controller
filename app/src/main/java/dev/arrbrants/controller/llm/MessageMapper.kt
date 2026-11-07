package dev.arrbrants.controller.llm

import dev.arrbrants.controller.data.AttachmentStore
import dev.arrbrants.controller.data.ChatMessage
import android.content.Context

/**
 * Converts stored [ChatMessage]s into prompt messages. Shared by the chat
 * request (short budget) and the agent loop (long budget), so attachment and
 * raw-DSML handling live in one place.
 */
object MessageMapper {

    const val CHAT_BUDGET_TOKENS = 12_000L
    const val AGENT_BUDGET_TOKENS = 6_000L

    /** Newest messages first, stopping once [budgetTokens] is reached. */
    fun history(
        context: Context,
        messages: List<ChatMessage>,
        budgetTokens: Long
    ): List<LlmClient.Msg> {
        val selected = ArrayList<LlmClient.Msg>()
        var tokens = 0L
        for (m in messages.asReversed()) {
            if (m.content.isBlank() && m.rawContent.isNullOrBlank() && m.attachments.isEmpty()) {
                continue
            }
            val msg = toLlm(context, m)
            if (tokens + msg.approxTokens > budgetTokens && selected.isNotEmpty()) break
            selected.add(0, msg)
            tokens += msg.approxTokens
        }
        return selected
    }

    /** Prompt messages for a chat/routing request, system prompt included. */
    fun chat(
        context: Context,
        messages: List<ChatMessage>,
        systemPrompt: String
    ): List<LlmClient.Msg> = ArrayList<LlmClient.Msg>(messages.size + 1).apply {
        add(LlmClient.Msg("system", systemPrompt))
        addAll(history(context, messages, CHAT_BUDGET_TOKENS))
    }

    /** Turns one stored message into a prompt message, inlining attachments. */
    fun toLlm(context: Context, m: ChatMessage): LlmClient.Msg {
        // Assistant steps carry the model's raw DSML reply; send that back
        // verbatim instead of the human-readable bubble.
        val base = if (m.role == "assistant") m.rawContent ?: m.content else m.content
        if (m.attachments.isEmpty()) return LlmClient.Msg(m.role, base)

        val images = AttachmentStore.imageDataUrls(context, m.attachments)
        val extra = StringBuilder()
        for (a in m.attachments) {
            if (a.isImage) continue
            val text = AttachmentStore.readText(context, a)
            if (text != null) {
                extra.append("\n\n[Attached file: ").append(a.name).append("]\n").append(text)
            } else {
                extra.append("\n\n[Attached file: ").append(a.name)
                    .append(" (").append(a.mime).append(", ").append(a.size).append(" bytes)")
                    .append(" — binary content not sent]")
            }
        }
        return LlmClient.Msg(m.role, base + extra, images, images.size * 1400)
    }
}
