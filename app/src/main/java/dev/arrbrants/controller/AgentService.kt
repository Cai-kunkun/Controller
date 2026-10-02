package dev.arrbrants.controller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
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
        private const val EXTRA_TASK = "task"
        private const val EXTRA_CONVERSATION = "conversation"
        private const val STEP_DELAY_MS = 1200L
        /** Hard cap so a very long run can't produce an oversized request body. */
        private const val MAX_CONTEXT_MESSAGES = 200
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

        fun start(context: Context, task: String, conversationId: Long) {
            val intent = Intent(context, AgentService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TASK, task)
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

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRequested = true
                if (running) updateNotification(getString(R.string.agent_stopping)) else stopSelf()
            }
            else -> {
                val task = intent?.getStringExtra(EXTRA_TASK)
                val conversationId = intent?.getLongExtra(EXTRA_CONVERSATION, -1L) ?: -1L
                if (task.isNullOrBlank() || conversationId < 0 || running) {
                    stopSelf()
                } else {
                    stopRequested = false
                    createChannel()
                    startForeground(NOTIF_ID, buildNotification(getString(R.string.agent_starting)))
                    Thread { runAgent(task, conversationId) }.start()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun runAgent(task: String, conversationId: Long) {
        running = true
        val app = applicationContext
        var overlay: AgentOverlay? = null
        try {
            val cfg = LlmClient.Config(
                prefs.getString(MainActivity.KEY_URL, "") ?: "",
                prefs.getString(MainActivity.KEY_API, "") ?: "",
                prefs.getString(MainActivity.KEY_MODEL, "") ?: ""
            )
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

            val msgs = ArrayList<LlmClient.Msg>()
            msgs.add(LlmClient.Msg("system", AgentProtocol.systemPrompt(task)))
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
                msgs.add(
                    LlmClient.Msg("user", AgentProtocol.stepPrompt(step, lastResult, notes, nudge), dataUrl)
                )
                nudge = null
                // Keep the request bounded: drop the oldest exchanges only when
                // the conversation grows very large (images are ~50KB each).
                while (msgs.size > MAX_CONTEXT_MESSAGES) msgs.removeAt(1)
                val parsed = try {
                    requestAction(cfg, msgs)
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

                if (action is AgentProtocol.Action.Done) {
                    val path = ScreenCapture.saveShot(app, conversationId, shot.jpeg)
                    appendMessage(
                        conversationId,
                        ChatMessage("assistant", getString(R.string.agent_done, action.summary), path)
                    )
                    overlay?.update(getString(R.string.agent_finished), action.summary, null)
                    finished = true
                    break
                }
                if (action is AgentProtocol.Action.Ask) {
                    val path = ScreenCapture.saveShot(app, conversationId, shot.jpeg)
                    appendMessage(
                        conversationId,
                        ChatMessage("assistant", getString(R.string.agent_ask, action.question), path)
                    )
                    overlay?.update(getString(R.string.agent_finished), action.question, null)
                    finished = true
                    break
                }

                val desc = AgentProtocol.describe(action)
                val header = getString(R.string.agent_step, step, desc)
                val shotPath = ScreenCapture.saveShot(app, conversationId, shot.jpeg)
                val bubble = if (screenStep.thought.isBlank()) header else "$header\n${screenStep.thought}"
                appendMessage(conversationId, ChatMessage("assistant", bubble, shotPath))
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

                val failure = execute(action)
                lastResult = failure ?: "ok"

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

    /** Asks the model for one action, giving it up to two chances to fix bad JSON. */
    private fun requestAction(cfg: LlmClient.Config, msgs: MutableList<LlmClient.Msg>): AgentProtocol.Step {
        var lastError: String? = null
        repeat(3) {
            val reply = LlmClient.send(cfg, msgs)
            msgs.add(LlmClient.Msg("assistant", reply))
            try {
                return AgentProtocol.parse(reply)
            } catch (e: IOException) {
                lastError = e.message
                msgs.add(
                    LlmClient.Msg(
                        "user",
                        "Your reply could not be used: ${e.message}. Reply with exactly one valid JSON object and nothing else."
                    )
                )
            }
        }
        throw IOException("model kept replying with invalid actions: $lastError")
    }

    /** Runs one action through root; returns an error string, or null on success. */
    private fun execute(action: AgentProtocol.Action): String? = try {
        when (action) {
            is AgentProtocol.Action.Tap -> run("input tap ${action.x} ${action.y}")
            is AgentProtocol.Action.DoubleTap -> run(
                "input tap ${action.x} ${action.y}; input tap ${action.x} ${action.y}"
            )
            is AgentProtocol.Action.LongPress -> run("input swipe ${action.x} ${action.y} ${action.x} ${action.y} 800")
            is AgentProtocol.Action.TapText -> tapByText(action.label)
            is AgentProtocol.Action.Swipe -> run(
                "input swipe ${action.x1} ${action.y1} ${action.x2} ${action.y2} ${action.durationMs}"
            )
            is AgentProtocol.Action.Text -> typeText(action.text)
            is AgentProtocol.Action.Key -> {
                val code = AgentProtocol.keyCode(action.key)
                if (code == null) "unknown key '${action.key}'" else run("input keyevent $code")
            }
            is AgentProtocol.Action.Wait -> {
                Thread.sleep(action.ms.toLong())
                null
            }
            else -> null
        }
    } catch (e: Exception) {
        e.message ?: "command failed"
    }

    private fun run(command: String): String? {
        val result = RootShell.exec(command)
        return if (result.ok) null
        else result.stderr.ifBlank { result.stdout }.ifBlank { "exit ${result.code}" }
    }

    /**
     * Finds a view by its label through `uiautomator dump` and taps its center.
     * Far more reliable than coordinate guesses for labeled buttons.
     */
    private fun tapByText(label: String): String? {
        val file = "/data/local/tmp/controller_ui.xml"
        val dump = RootShell.exec(
            "uiautomator dump $file >/dev/null 2>&1; cat $file 2>/dev/null; rm -f $file",
            20000
        )
        if (dump.stdout.isBlank() || dump.stdout.length < 500) {
            return "no UI labels available (this app hides its accessibility tree); use tap with coordinates read from the grid"
        }
        val center = UiDump.findCenter(dump.stdout, label)
            ?: return "no view labeled \"$label\""
        return run("input tap ${center.first} ${center.second}")
    }

    /**
     * Types into the focused field. `input text` handles ASCII reliably;
     * anything else (Chinese, emoji, …) goes through the clipboard plus a
     * synthetic KEYCODE_PASTE, which `input text` cannot express.
     */
    private fun typeText(text: String): String? {
        if (text.all { it.code in 0x20..0x7e }) {
            return run("input text ${RootShell.shellQuote(text)}")
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("controller", text))
        mainHandler.postDelayed({ clipboard.clearPrimaryClip() }, 3000)
        Thread.sleep(250)
        return run("input keyevent KEYCODE_PASTE")
    }

    /** Appends one message to the conversation, re-reading the store so nothing gets lost. */
    private fun appendMessage(conversationId: Long, message: ChatMessage) {
        val all = ConversationStore.loadAll(prefs)
        val conversation = all.find { it.id == conversationId }
        if (conversation == null) {
            all.add(
                0,
                ConversationStore.Conversation(
                    conversationId,
                    getString(R.string.agent_chat_title),
                    mutableListOf(message)
                )
            )
        } else {
            conversation.messages.add(message)
        }
        ConversationStore.saveAll(prefs, all)
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
