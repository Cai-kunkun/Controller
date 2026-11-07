package dev.arrbrants.controller.agent

import dev.arrbrants.controller.MainActivity
import dev.arrbrants.controller.R
import dev.arrbrants.controller.data.AppPrefs
import dev.arrbrants.controller.data.ChatMessage
import dev.arrbrants.controller.data.ConversationStore
import dev.arrbrants.controller.data.StatsStore
import dev.arrbrants.controller.llm.LlmClient
import dev.arrbrants.controller.llm.MessageMapper
import dev.arrbrants.controller.llm.TokenBudget
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.IOException

/**
 * Runs the screenshot -> LLM -> action loop as a foreground service so it
 * keeps going while Controller itself is in the background and the agent
 * operates the actual device UI.
 */
class AgentService : Service() {

    companion object {
        private const val CHANNEL_ID = "agent"
        private const val NOTIF_ID = 42
        private const val EXTRA_CONVERSATION = "conversation"
        private const val STEP_DELAY_MS = 1200L
        /** Compress the conversation once the prompt reaches this share of the window. */
        private const val COMPACT_AT = 0.90
        /** How many of the most recent exchanges survive a compaction (3 steps). */
        private const val KEEP_TAIL_MESSAGES = 6
        /** Cap the per-conversation action log carried into a compaction summary. */
        private const val STEP_LOG_MAX = 200
        /** Belt-and-braces cap in case the provider reports no token usage at all. */
        private const val MAX_MESSAGES_SAFETY = 300
        /** Effectively unlimited; the user can stop anytime, and a stuck-loop guard still applies. */
        const val MAX_STEPS = Int.MAX_VALUE
        private const val REPEAT_NUDGE = 4
        private const val REPEAT_STOP = 10
        const val ACTION_START = "dev.arrbrants.controller.AGENT_START"
        const val ACTION_STOP = "dev.arrbrants.controller.AGENT_STOP"

        /** True while the loop is executing; read by the UI. */
        @Volatile var running = false
            private set

        @Volatile private var stopRequested = false

        fun start(context: Context, conversationId: Long) {
            val intent = Intent(context, AgentService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONVERSATION, conversationId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            if (!running) {
                // Nothing to stop; avoids starting a service from the background.
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(NOTIF_ID)
                return
            }
            context.startService(
                Intent(context, AgentService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())
    /** Executes root actions; the service only wires progress + stop checks. */
    private val executor by lazy {
        ActionExecutor(this, mainHandler, { stopRequested }) { label, text ->
            updateNotification(text)
            overlay?.update(label, text, null)
        }
    }
    /** Kept as a field so a running sequence can refresh the banner per action. */
    private var overlay: AgentOverlay? = null
    /** Real prompt size from the last successful reply; drives compaction. */
    @Volatile private var lastPromptTokens = 0L
    /** Raw text of the model's latest reply, echoed back to it in history. */
    @Volatile private var lastReplyText = ""
    /** Latest DeepSeek chain of thought, stored with the step message. */
    @Volatile private var lastReasoningText = ""
    /** Tool call ids awaiting a result message before the next request. */
    private var pendingToolCallIds: List<String> = emptyList()

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs.get(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRequested = true
                if (running) updateNotification(getString(R.string.agent_stopping)) else stopSelf()
            }
            else -> {
                val conversationId = intent?.getLongExtra(EXTRA_CONVERSATION, -1L) ?: -1L
                if (conversationId < 0 || running) {
                    stopSelf()
                } else {
                    stopRequested = false
                    createChannel()
                    startForeground(NOTIF_ID, buildNotification(getString(R.string.agent_starting)))
                    Thread { runAgent(conversationId) }.start()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun runAgent(conversationId: Long) {
        running = true
        lastPromptTokens = 0L
        lastReasoningText = ""
        pendingToolCallIds = emptyList()
        val app = applicationContext
        try {
            val cfg = AppPrefs.config(this)
            if (cfg.baseUrl.isBlank() || cfg.model.isBlank()) {
                appendMessage(conversationId, ChatMessage("assistant", getString(R.string.need_setup)))
                return
            }
            if (!RootShell.isRootAvailable()) {
                appendMessage(conversationId, ChatMessage("assistant", getString(R.string.agent_need_root)))
                return
            }

            // Ask the low-memory killer to spare us while a heavy app (game) runs.
            RootShell.exec("echo -300 > /proc/${Process.myPid()}/oom_score_adj")

            // Status banner on top of everything, so the user can watch along.
            if (ensureOverlayPermission()) {
                overlay = AgentOverlay(this)
                overlay?.show()
                overlay?.update(getString(R.string.agent_starting), "", null)
            }

            // Leave Controller so the agent sees (and works on) the real device UI.
            // If the task was started while another app is in the foreground,
            // keep that context instead.
            val focused = RootShell.exec("dumpsys window | grep mCurrentFocus", 8000).stdout
            if (focused.contains(packageName)) {
                RootShell.exec("input keyevent KEYCODE_HOME")
                Thread.sleep(900)
            }

            val systemMsg = LlmClient.Msg("system", AgentProtocol.systemPrompt())
            val seed = ConversationStore.loadAll(prefs).find { it.id == conversationId }
            val msgs = ArrayList<LlmClient.Msg>()
            msgs.add(systemMsg)
            // Seed the loop with the conversation history (raw DSML + attachments).
            msgs.addAll(
                MessageMapper.history(
                    this,
                    seed?.messages ?: emptyList(),
                    MessageMapper.AGENT_BUDGET_TOKENS
                )
            )
            val stepLog = ArrayList<String>()
            val contextLimit = AppPrefs.contextWindow(this)
            var notes: String? = null
            var lastResult: String? = null
            var nudge: String? = null
            var step = 0
            var finished = false
            var lastSignature = ""
            var repeatCount = 0
            var nudged = false
            while (step < MAX_STEPS && !stopRequested) {
                step++
                val shot = try {
                    // Hide the status banner for the capture itself: the model
                    // should see the raw screen, not our overlay.
                    overlay?.hideForCapture()
                    Thread.sleep(250)
                    val s = ScreenCapture.capture(app)
                    overlay?.showAfterCapture()
                    s
                } catch (e: Exception) {
                    overlay?.showAfterCapture()
                    appendMessage(
                        conversationId,
                        ChatMessage("assistant", getString(R.string.agent_error, e.message ?: "screenshot failed"))
                    )
                    return
                }
                updateNotification(getString(R.string.agent_running, step))
                overlay?.update(getString(R.string.agent_running, step), "", null)

                val dataUrl = "data:image/jpeg;base64," +
                    Base64.encodeToString(shot.jpeg, Base64.NO_WRAP)
                val userMsg = LlmClient.Msg(
                    "user",
                    AgentProtocol.stepPrompt(step, lastResult, notes, nudge),
                    listOf(dataUrl),
                    TokenBudget.image(shot.sentWidth, shot.sentHeight)
                )
                nudge = null
                // Compact before the prompt crosses 90% of the context window.
                // The previous reply's real prompt size is authoritative; the
                // local estimate only has to cover the newest screenshot.
                val projected =
                    maxOf(lastPromptTokens, approxTokens(msgs)) + userMsg.approxTokens
                val tooLarge = projected >= (contextLimit * COMPACT_AT).toLong() ||
                    msgs.size >= MAX_MESSAGES_SAFETY
                if (tooLarge) {
                    compact(
                        msgs, systemMsg, stepLog, notes, step, contextLimit, conversationId
                    )
                }
                msgs.add(userMsg)
                val parsed = try {
                    requestAction(cfg, msgs, conversationId)
                } catch (e: FinalAnswer) {
                    // The model chose to answer in plain text instead of acting.
                    appendMessage(
                        conversationId,
                        ChatMessage("assistant", e.text, reasoning = reasoningOrNull())
                    )
                    overlay?.update(getString(R.string.agent_finished), e.text.take(120), null)
                    finished = true
                    break
                } catch (e: Exception) {
                    appendMessage(
                        conversationId,
                        ChatMessage("assistant", getString(R.string.agent_error, e.message ?: "request failed"))
                    )
                    return
                }
                val screenStep = AgentProtocol.toScreenSpace(parsed, shot)
                if (parsed.notes != null) notes = parsed.notes
                val action = screenStep.action

                if (action is AgentProtocol.Action.Look) {
                    // No device interaction: just refresh the screen and decide.
                    updateNotification(getString(R.string.agent_running, step))
                    overlay?.update(
                        getString(R.string.agent_running, step),
                        getString(R.string.agent_looking),
                        screenStep.thought
                    )
                    stepLog.add("$step. look at the screen")
                    if (stepLog.size > STEP_LOG_MAX) stepLog.removeAt(0)
                    finishToolCalls(msgs, "ok")
                    if (!stopRequested) Thread.sleep(400)
                    continue
                }

                if (action is AgentProtocol.Action.Done) {
                    val path = ScreenCapture.saveShot(app, conversationId, shot.jpeg)
                    appendMessage(
                        conversationId,
                        ChatMessage(
                            "assistant",
                            getString(R.string.agent_done, action.summary),
                            path,
                            rawContent = lastReplyText,
                            reasoning = reasoningOrNull()
                        )
                    )
                    overlay?.update(getString(R.string.agent_finished), action.summary, null)
                    finished = true
                    break
                }
                // A sequence must never hide a done/ask, and parsing already rejects them
                // inside a chain, so only a bare done/ask can end the run here.
                if (action is AgentProtocol.Action.Ask) {
                    val path = ScreenCapture.saveShot(app, conversationId, shot.jpeg)
                    appendMessage(
                        conversationId,
                        ChatMessage(
                            "assistant",
                            getString(R.string.agent_ask, action.question),
                            path,
                            rawContent = lastReplyText,
                            reasoning = reasoningOrNull()
                        )
                    )
                    overlay?.update(getString(R.string.agent_finished), action.question, null)
                    finished = true
                    break
                }

                val desc = AgentProtocol.describe(action)
                val header = getString(R.string.agent_step, step, desc)
                val shotPath = ScreenCapture.saveShot(app, conversationId, shot.jpeg)
                val thought = AgentProtocol.cleanThought(screenStep.thought)
                val bubble = if (thought.isBlank()) header else "$header\n$thought"
                appendMessage(
                    conversationId,
                    ChatMessage(
                        "assistant",
                        bubble,
                        shotPath,
                        rawContent = lastReplyText,
                        reasoning = reasoningOrNull()
                    )
                )
                updateNotification(header)
                overlay?.update(getString(R.string.agent_running, step), desc, screenStep.thought)

                // Stuck-loop guard: same kind of action over and over without progress.
                val signature = AgentProtocol.actionSignature(action)
                if (signature == lastSignature) {
                    repeatCount++
                } else {
                    lastSignature = signature
                    repeatCount = 1
                    nudged = false
                }
                if (repeatCount == REPEAT_NUDGE && !nudged) {
                    nudged = true
                    nudge = "You have repeated the same action several times without progress; try a different approach."
                } else if (repeatCount >= REPEAT_STOP) {
                    appendMessage(
                        conversationId,
                        ChatMessage("assistant", getString(R.string.agent_repeat_stop))
                    )
                    break
                }

                val failure = executor.execute(action, getString(R.string.agent_running, step))
                lastResult = failure ?: "ok"
                finishToolCalls(msgs, lastResult)
                stepLog.add("$step. ${AgentProtocol.describe(action)} → ${failure ?: "ok"}")
                if (stepLog.size > STEP_LOG_MAX) stepLog.removeAt(0)

                if (!stopRequested) Thread.sleep(STEP_DELAY_MS)
            }

            when {
                stopRequested -> appendMessage(
                    conversationId,
                    ChatMessage("assistant", getString(R.string.agent_stopped, step))
                )
                !finished && step >= MAX_STEPS -> appendMessage(
                    conversationId,
                    ChatMessage("assistant", getString(R.string.agent_max_steps, MAX_STEPS))
                )
            }
        } catch (e: Exception) {
            appendMessage(
                conversationId,
                ChatMessage("assistant", getString(R.string.agent_error, e.message ?: "unknown error"))
            )
        } finally {
            running = false
            stopRequested = false
            overlay?.let { o -> mainHandler.postDelayed({ o.dismiss() }, 4000) }
            updateNotification(getString(R.string.agent_finished), ongoing = false)
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    /** Root-grants the overlay permission so the status banner can be shown. */
    private fun ensureOverlayPermission(): Boolean {
        if (Settings.canDrawOverlays(this)) return true
        RootShell.exec("appops set $packageName SYSTEM_ALERT_WINDOW allow")
        return Settings.canDrawOverlays(this)
    }

    private fun approxTokens(msgs: List<LlmClient.Msg>): Long {
        var total = 0L
        for (m in msgs) total += m.approxTokens
        return total
    }

    /**
     * Drops the old screenshots/exchanges once the prompt reaches 90% of the
     * context window and replaces them with a compact summary of what already
     * happened. One cache miss here is paid back by many cached steps after it,
     * unlike trimming the head one message at a time (which misses every step).
     */
    private fun compact(
        msgs: MutableList<LlmClient.Msg>,
        systemMsg: LlmClient.Msg,
        stepLog: List<String>,
        notes: String?,
        step: Int,
        contextLimit: Int,
        conversationId: Long
    ) {
        val before = approxTokens(msgs)
        // Never carry the system message into the tail: it is re-added above.
        val tail = ArrayList(msgs.drop(1).takeLast(KEEP_TAIL_MESSAGES))
        msgs.clear()
        msgs.add(systemMsg)
        msgs.add(LlmClient.Msg("user", compactionSummary(step, stepLog, notes)))
        msgs.add(LlmClient.Msg("assistant", "Understood. I will continue from the current screen."))
        msgs.addAll(tail)
        // On a small window even a few screenshots are too much: drop the oldest
        // kept exchanges until the fresh context is comfortably under the limit.
        val target = (contextLimit * 0.55).toLong()
        while (approxTokens(msgs) > target && msgs.size > 4) msgs.removeAt(3)
        lastPromptTokens = 0L
        val after = approxTokens(msgs)
        StatsStore.bumpCompaction(prefs, conversationId)
        appendMessage(
            conversationId,
            ChatMessage(
                "assistant",
                getString(
                    R.string.agent_compacted,
                    TokenBudget.format(before),
                    TokenBudget.format(after)
                )
            )
        )
    }

    private fun compactionSummary(step: Int, stepLog: List<String>, notes: String?): String {
        val sb = StringBuilder()
        sb.append("[CONTEXT COMPRESSED at step ").append(step).append("]\n")
        sb.append("Earlier screenshots and exchanges were removed to stay inside the model's ")
        sb.append("context window. Continue the task from the current screenshot and do not ")
        sb.append("repeat work that is already done.\n")
        if (!notes.isNullOrBlank()) {
            sb.append("\nYour persistent notes:\n").append(notes).append('\n')
        }
        if (stepLog.isNotEmpty()) {
            val recent = stepLog.takeLast(60)
            sb.append("\nActions already executed:\n")
            if (stepLog.size > recent.size) {
                sb.append("… ").append(stepLog.size - recent.size).append(" earlier steps omitted.\n")
            }
            for (line in recent) sb.append(line).append('\n')
        }
        sb.append("\nReply with exactly one JSON action.")
        return sb.toString()
    }

    /** Thrown when the model answers in plain text instead of emitting an action. */
    private class FinalAnswer(val text: String) : Exception()

    /** Answers every pending native tool call so the next request stays valid. */
    private fun reasoningOrNull(): String? =
        lastReasoningText.takeIf { it.isNotBlank() }

    /** Answers every pending native tool call so the next request stays valid. */
    private fun finishToolCalls(msgs: MutableList<LlmClient.Msg>, result: String) {
        if (pendingToolCallIds.isEmpty()) return
        for (id in pendingToolCallIds) {
            msgs.add(LlmClient.Msg("tool", result, toolCallId = id))
        }
        pendingToolCallIds = emptyList()
    }

    /** Asks the model for one action, giving it up to two chances to fix bad JSON. */
    private fun requestAction(
        cfg: LlmClient.Config,
        msgs: MutableList<LlmClient.Msg>,
        conversationId: Long
    ): AgentProtocol.Step {
        var lastError: String? = null
        repeat(3) {
            val reply = LlmClient.send(cfg, msgs)
            lastPromptTokens = reply.usage.promptTokens
            lastReasoningText = reply.reasoning
            StatsStore.record(prefs, conversationId, reply.usage)

            // Native function calling: structured tool_calls in the reply.
            val toolStep = AgentProtocol.stepFromToolCalls(reply.toolCalls)
            if (toolStep != null) {
                lastReplyText = AgentProtocol.toDsml(reply.toolCalls)
                pendingToolCallIds = reply.toolCalls.map { it.id }
                msgs.add(LlmClient.Msg("assistant", reply.text, toolCalls = reply.toolCalls))
                return toolStep
            }
            if (reply.toolCalls.isNotEmpty()) {
                // Tool call we could not parse: keep the protocol valid and retry.
                lastError = "invalid tool call arguments"
                msgs.add(LlmClient.Msg("assistant", reply.text, toolCalls = reply.toolCalls))
                for (call in reply.toolCalls) {
                    msgs.add(
                        LlmClient.Msg("tool", "error: invalid arguments", toolCallId = call.id)
                    )
                }
                msgs.add(
                    LlmClient.Msg(
                        "user",
                        "That tool call was invalid. Call device_action again with a valid " +
                            "action and its fields, or reply in plain text to answer the user."
                    )
                )
                return@repeat
            }

            lastReplyText = reply.text
            msgs.add(LlmClient.Msg("assistant", reply.text))
            AgentProtocol.asActionOrNull(reply.text)?.let { return it }
            val trimmed = reply.text.trim()
            if (!AgentProtocol.looksLikeActionStart(trimmed)) {
                // The model decided to answer the user instead of continuing.
                throw FinalAnswer(reply.text)
            }
            lastError = "invalid action"
            msgs.add(
                LlmClient.Msg(
                    "user",
                    "Your reply could not be used. Reply with exactly one device_action DSML " +
                        "tool call, or with plain text to answer the user and stop."
                )
            )
        }
        throw IOException("model kept replying with invalid actions: $lastError")
    }

    /** Appends one message to the conversation, re-reading the store so nothing gets lost. */
    private fun appendMessage(conversationId: Long, message: ChatMessage) {
        ConversationStore.append(
            prefs, conversationId, message, getString(R.string.agent_chat_title)
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.agent_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun buildNotification(text: String, ongoing: Boolean = true): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, AgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agent)
            .setContentTitle(getString(R.string.agent_notif_title))
            .setContentText(text)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.agent_stop), stop)
            .build()
    }

    private fun updateNotification(text: String, ongoing: Boolean = true) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text, ongoing))
    }
}
