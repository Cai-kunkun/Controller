package dev.arrbrants.controller

import dev.arrbrants.controller.agent.AgentProtocol
import dev.arrbrants.controller.agent.AgentService
import dev.arrbrants.controller.agent.ScreenCapture
import dev.arrbrants.controller.data.AppPrefs
import dev.arrbrants.controller.data.Attachment
import dev.arrbrants.controller.data.AttachmentStore
import dev.arrbrants.controller.data.ChatMessage
import dev.arrbrants.controller.data.ConversationStore
import dev.arrbrants.controller.data.StatsStore
import dev.arrbrants.controller.llm.DeepSeekBalance
import dev.arrbrants.controller.llm.LlmClient
import dev.arrbrants.controller.llm.MessageMapper
import dev.arrbrants.controller.llm.TokenBudget
import dev.arrbrants.controller.ui.ChatAdapter
import dev.arrbrants.controller.ui.ConversationAdapter
import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        const val TITLE_MAX = 24
    }

    private var history: MutableList<ChatMessage> = ArrayList()
    private var conversations: MutableList<ConversationStore.Conversation> = ArrayList()
    private var currentId = -1L

    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: ChatAdapter
    private lateinit var drawerAdapter: ConversationAdapter
    private lateinit var list: RecyclerView
    private lateinit var drawerList: RecyclerView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var input: EditText
    private lateinit var emptyState: View
    private lateinit var statsCard: MaterialCardView
    private lateinit var ctxBar: LinearProgressIndicator
    private lateinit var ctxText: TextView
    private lateinit var compactBadge: TextView
    private lateinit var statHit: TextView
    private lateinit var statMiss: TextView
    private lateinit var statRate: TextView
    private lateinit var statTps: TextView
    private lateinit var balanceText: TextView
    private lateinit var statsBody: View
    private lateinit var statsToggle: ImageView
    private lateinit var pendingRow: LinearLayout
    private lateinit var pendingScroll: View
    private var statsCollapsed = false
    private val pending = ArrayList<Attachment>()
    private var busy = false
    private var autoScroll = true

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (!uris.isNullOrEmpty()) importAttachments(uris)
        }

    private val handler = Handler(Looper.getMainLooper())
    private val liveRefresh = object : Runnable {
        override fun run() {
            reloadFromPrefs()
            if (AgentService.running) handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = AppPrefs.get(this)
        conversations = ConversationStore.loadAll(prefs)
        currentId = AppPrefs.currentConversation(this)
        val current = conversations.find { it.id == currentId }
        if (current != null) {
            history = current.messages
        } else {
            currentId = -1L
        }

        drawerLayout = findViewById(R.id.drawerLayout)
        list = findViewById(R.id.list)
        drawerList = findViewById(R.id.drawerList)
        input = findViewById(R.id.input)
        emptyState = findViewById(R.id.emptyState)
        statsCard = findViewById(R.id.statsCard)
        ctxBar = findViewById(R.id.ctxBar)
        ctxText = findViewById(R.id.ctxText)
        compactBadge = findViewById(R.id.compactBadge)
        statHit = findViewById(R.id.statHit)
        statMiss = findViewById(R.id.statMiss)
        statRate = findViewById(R.id.statRate)
        statTps = findViewById(R.id.statTps)
        balanceText = findViewById(R.id.balanceText)
        statsBody = findViewById(R.id.statsBody)
        statsToggle = findViewById(R.id.statsToggle)
        pendingRow = findViewById(R.id.pendingRow)
        pendingScroll = findViewById(R.id.pendingScroll)
        adapter = ChatAdapter { history }
        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.adapter = adapter
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    // Follow new tokens only while the user is at the bottom.
                    autoScroll = !recyclerView.canScrollVertically(1)
                }
            }
        })
        drawerAdapter = ConversationAdapter(
            { conversations },
            { currentId },
            onOpen = { openConversation(it) },
            onDelete = { deleteConversation(it) }
        )
        drawerList.layoutManager = LinearLayoutManager(this)
        drawerList.adapter = drawerAdapter

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { drawerLayout.openDrawer(Gravity.START) }

        findViewById<View>(R.id.btnNewChat).setOnClickListener { newChat() }
        findViewById<View>(R.id.btnAttach).setOnClickListener {
            filePicker.launch(arrayOf("*/*"))
        }
        findViewById<View>(R.id.statsHeader).setOnClickListener { toggleStats() }
        statsCollapsed = AppPrefs.statsCollapsed(this)
        applyStatsCollapsed()

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send()
                true
            } else {
                false
            }
        }
        findViewById<View>(R.id.btnSend).setOnClickListener { send() }
        refreshUi()
        refreshDrawer()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_stop)?.isVisible = AgentService.running
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_stop -> {
                AgentService.stop(this)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        reloadFromPrefs()
        if (AgentService.running) handler.post(liveRefresh)
        refreshBalance()
        invalidateOptionsMenu()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(liveRefresh)
        // While the agent runs, the service owns the store; don't overwrite it.
        if (!AgentService.running) {
            ConversationStore.saveAll(prefs, conversations)
        }
        AppPrefs.setCurrentConversation(this, currentId)
    }

    private fun reloadFromPrefs() {
        conversations = ConversationStore.loadAll(prefs)
        currentId = prefs.getLong(AppPrefs.KEY_CURRENT, currentId)
        val current = conversations.find { it.id == currentId }
        history = current?.messages ?: ArrayList()
        if (current == null) currentId = -1L
        refreshUi()
        refreshDrawer()
    }

    private fun newChat() {
        drawerLayout.closeDrawer(Gravity.START)
        clearPending()
        history = ArrayList()
        currentId = -1L
        AppPrefs.setCurrentConversation(this, currentId)
        refreshUi()
        refreshDrawer()
    }

    private fun openConversation(conv: ConversationStore.Conversation) {
        drawerLayout.closeDrawer(Gravity.START)
        clearPending()
        currentId = conv.id
        history = conv.messages
        AppPrefs.setCurrentConversation(this, currentId)
        refreshUi()
        refreshDrawer()
    }

    private fun deleteConversation(conv: ConversationStore.Conversation) {
        conversations.remove(conv)
        ScreenCapture.deleteShots(this, conv.messages)
        for (m in conv.messages) {
            AttachmentStore.delete(this, m.attachments)
        }
        StatsStore.remove(prefs, conv.id)
        if (currentId == conv.id) {
            history = ArrayList()
            currentId = -1L
            refreshUi()
        }
        ConversationStore.saveAll(prefs, conversations)
        refreshDrawer()
    }

    /**
     * One unified path: the message is answered by the model, which may either
     * reply in plain text (chat) or emit a JSON action (operate the phone).
     * Chat replies stream into the bubble token by token.
     */
    private fun send() {
        if (AgentService.running) {
            Toast.makeText(this, R.string.agent_already_running, Toast.LENGTH_SHORT).show()
            return
        }
        if (busy) return
        val text = input.text.toString().trim()
        if (text.isEmpty() && pending.isEmpty()) return

        val cfg = AppPrefs.config(this)
        if (cfg.baseUrl.isBlank()) {
            Toast.makeText(this, R.string.need_setup, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        input.setText("")
        val outgoing = ArrayList(pending)
        pending.clear()
        refreshPending()
        history.add(ChatMessage("user", text, null, outgoing))
        syncCurrent()
        val conversationId = currentId
        val snapshot = history.toList()

        history.add(ChatMessage("assistant", getString(R.string.thinking)))
        val index = history.size - 1
        adapter.notifyItemInserted(index)
        emptyState.visibility = View.GONE
        autoScroll = true
        list.scrollToPosition(index)
        busy = true

        Thread {
            val requestMessages = MessageMapper.chat(
                this, snapshot, AgentProtocol.systemPrompt(AppPrefs.persona(this))
            )
            val builder = StringBuilder()
            val reasoningBuilder = StringBuilder()
            var contentStarted = false
            var lastReasoningPost = 0L
            var decided = false
            var plainText = false
            var lastPost = 0L
            var error: Exception? = null

            val reply = try {
                LlmClient.stream(
                    cfg,
                    requestMessages,
                    onReasoning = { piece ->
                        reasoningBuilder.append(piece)
                        if (!contentStarted) {
                            val now = System.currentTimeMillis()
                            if (now - lastReasoningPost >= 60) {
                                lastReasoningPost = now
                                val snapshotReasoning = reasoningBuilder.toString()
                                runOnUiThread {
                                    updateBubble(
                                        index,
                                        "",
                                        reasoning = snapshotReasoning,
                                        reasoningExpanded = true
                                    )
                                }
                            }
                        }
                    }
                ) { delta ->
                    builder.append(delta)
                    if (!decided) {
                        val start = builder.toString().trimStart()
                        if (start.isNotEmpty()) {
                            decided = true
                            plainText = !AgentProtocol.looksLikeActionStart(start)
                        }
                    }
                    if (!contentStarted) {
                        contentStarted = true
                        val reasoning = reasoningBuilder.toString().takeIf { it.isNotBlank() }
                        val display = if (plainText) builder.toString() else getString(R.string.thinking)
                        runOnUiThread {
                            updateBubble(
                                index,
                                display,
                                reasoning = reasoning,
                                reasoningExpanded = false
                            )
                        }
                    }
                    val now = System.currentTimeMillis()
                    if (decided && plainText && now - lastPost >= 60) {
                        lastPost = now
                        val snapshot = builder.toString()
                        val reasoning = reasoningBuilder.toString().takeIf { it.isNotBlank() }
                        runOnUiThread {
                            updateBubble(
                                index,
                                snapshot,
                                reasoning = reasoning,
                                reasoningExpanded = false
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                error = e
                null
            }

            runOnUiThread {
                busy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (reply == null) {
                    updateBubble(index, "Error: ${error?.message}")
                } else {
                    routeReply(index, conversationId, reply)
                    refreshBalance()
                }
                syncCurrent()
                refreshUi()
                refreshDrawer()
            }
        }.start()
    }

    /**
     * Decides what a completed reply means: a chat answer stays in the bubble,
     * a JSON action hands the conversation over to the automation service.
     */
    private fun routeReply(index: Int, conversationId: Long, reply: LlmClient.Reply) {
        if (conversationId >= 0 && reply.usage.promptTokens > 0) {
            StatsStore.record(prefs, conversationId, reply.usage)
        }
        val step = AgentProtocol.stepFromToolCalls(reply.toolCalls)
            ?: AgentProtocol.asActionOrNull(reply.text)
        val pluginCall = reply.toolCalls.any { it.name.startsWith("plugin_") } ||
            AgentProtocol.asPluginCallOrNull(reply.text) != null
        when {
            pluginCall -> startAgent(index, conversationId)

            step == null -> updateBubble(
                index, reply.text, reply.reasoning, reasoningExpanded = false
            )

            step.action is AgentProtocol.Action.Done ->
                updateBubble(
                    index,
                    getString(R.string.agent_done, step.action.summary),
                    reply.reasoning,
                    reasoningExpanded = false
                )

            step.action is AgentProtocol.Action.Ask ->
                updateBubble(
                    index,
                    getString(R.string.agent_ask, step.action.question),
                    reply.reasoning,
                    reasoningExpanded = false
                )

            else -> startAgent(index, conversationId)
        }
    }

    /** Hands the conversation to the foreground service for tools/screenshots. */
    private fun startAgent(index: Int, conversationId: Long) {
        updateBubble(index, getString(R.string.agent_auto_start))
        ensureNotificationPermission()
        AgentService.start(this, conversationId)
        invalidateOptionsMenu()
        moveTaskToBack(true)
    }

    /** Updates one bubble in place while a reply streams in. */
    private fun updateBubble(
        index: Int,
        text: String,
        reasoning: String? = null,
        reasoningExpanded: Boolean? = null
    ) {
        if (isFinishing || isDestroyed) return
        if (index !in history.indices) return
        history[index] = history[index].copy(
            content = text,
            reasoning = if (reasoning == null) {
                history[index].reasoning
            } else {
                reasoning.takeIf { it.isNotBlank() }
            },
            reasoningExpanded = reasoningExpanded ?: history[index].reasoningExpanded
        )
        adapter.notifyItemChanged(index)
        if (autoScroll) list.scrollToPosition(history.size - 1)
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /** Copies picked documents into app storage and stages them for the next send. */
    private fun importAttachments(uris: List<Uri>) {
        val conversationId = currentId
        Thread {
            val imported = ArrayList<Attachment>()
            for (uri in uris) {
                try {
                    AttachmentStore.copyIn(this, conversationId, uri)?.let { imported.add(it) }
                } catch (e: Exception) {
                    // Unreadable file: skip it rather than fail the whole picker.
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (imported.isNotEmpty()) {
                    pending.addAll(imported)
                    refreshPending()
                }
            }
        }.start()
    }

    private fun refreshPending() {
        pendingRow.removeAllViews()
        pendingScroll.visibility = if (pending.isEmpty()) View.GONE else View.VISIBLE
        for (a in pending) {
            val chip = layoutInflater
                .inflate(R.layout.item_attachment_chip, pendingRow, false) as TextView
            chip.text = (if (a.isImage) "🖼 " else "📎 ") + a.name
            chip.setOnClickListener {
                pending.remove(a)
                AttachmentStore.delete(this, listOf(a))
                refreshPending()
            }
            pendingRow.addView(chip)
        }
    }

    private fun clearPending() {
        if (pending.isNotEmpty()) {
            AttachmentStore.delete(this, pending.toList())
            pending.clear()
        }
        refreshPending()
    }

    private fun toggleStats() {
        statsCollapsed = !statsCollapsed
        prefs.edit().putBoolean(AppPrefs.KEY_STATS_COLLAPSED, statsCollapsed).apply()
        applyStatsCollapsed()
    }

    private fun applyStatsCollapsed() {
        statsBody.visibility = if (statsCollapsed) View.GONE else View.VISIBLE
        statsToggle.setImageResource(
            if (statsCollapsed) R.drawable.ic_expand_more else R.drawable.ic_expand_less
        )
        statsToggle.contentDescription = getString(
            if (statsCollapsed) R.string.stats_expand else R.string.stats_collapse
        )
    }

    /** Registers a fresh conversation on the first message and persists state. */
    private fun syncCurrent() {
        if (currentId == -1L) {
            val first = history.firstOrNull { it.role == "user" }
            val title = first?.let { m ->
                m.content.take(TITLE_MAX).ifBlank {
                    m.attachments.firstOrNull()?.name?.take(TITLE_MAX)
                        ?: getString(R.string.new_chat)
                }
            } ?: getString(R.string.new_chat)
            val conv = ConversationStore.Conversation(
                System.currentTimeMillis(), title, history
            )
            conversations.add(0, conv)
            currentId = conv.id
        }
        ConversationStore.saveAll(prefs, conversations)
        AppPrefs.setCurrentConversation(this, currentId)
    }

    private fun refreshUi() {
        adapter.notifyDataSetChanged()
        val empty = history.isEmpty()
        emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        if (!empty) {
            list.scrollToPosition(history.size - 1)
        }
        updateToolbarSubtitle()
        refreshStats()
    }

    private fun updateToolbarSubtitle() {
        val model = prefs.getString(AppPrefs.KEY_MODEL, "")?.trim().orEmpty()
        toolbar.subtitle = if (model.isEmpty()) getString(R.string.app_subtitle) else model
    }

    private fun refreshStats() {
        val stats = StatsStore.load(prefs, currentId)
        val limit = AppPrefs.contextWindow(this)
            .coerceAtLeast(1024)
        val show = history.isNotEmpty() || AgentService.running || stats.requests > 0
        statsCard.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return

        statHit.text = TokenBudget.format(stats.cacheHit)
        statMiss.text = TokenBudget.format(stats.cacheMiss)
        statRate.text = if (stats.cacheTotal > 0) {
            String.format(Locale.US, "%.1f%%", stats.hitRate * 100)
        } else {
            "—"
        }
        statTps.text = if (stats.requests > 0) {
            String.format(Locale.US, "%.1f", stats.lastTps)
        } else {
            "—"
        }

        val used = stats.lastPromptTokens
        ctxText.text = getString(
            R.string.ctx_usage,
            TokenBudget.format(used),
            TokenBudget.format(limit.toLong())
        )
        val percent = if (limit <= 0) 0 else ((used * 100) / limit).toInt()
        ctxBar.setProgressCompat(percent.coerceIn(0, 100), true)
        compactBadge.visibility = if (stats.compactions > 0) View.VISIBLE else View.GONE
        compactBadge.text = getString(R.string.ctx_compacted, stats.compactions)
    }

    private fun refreshDrawer() {
        findViewById<View>(R.id.drawerEmpty).visibility =
            if (conversations.isEmpty()) View.VISIBLE else View.GONE
        drawerAdapter.notifyDataSetChanged()
    }

    private var balanceLoading = false
    private var lastBalanceFetch = 0L

    /** Shows the remaining DeepSeek credit when the official API is configured. */
    private fun refreshBalance(force: Boolean = false) {
        val cfg = AppPrefs.config(this)
        if (!DeepSeekBalance.isOfficial(cfg.baseUrl) || cfg.apiKey.isBlank()) {
            balanceText.visibility = View.GONE
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastBalanceFetch < 30_000) return
        if (balanceLoading) return
        balanceLoading = true
        lastBalanceFetch = now
        Thread {
            val balance = DeepSeekBalance.fetch(cfg.baseUrl, cfg.apiKey)
            runOnUiThread {
                balanceLoading = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (balance == null) {
                    balanceText.visibility = View.GONE
                } else {
                    balanceText.text = getString(R.string.balance_format, balance.display)
                    balanceText.visibility = View.VISIBLE
                }
            }
        }.start()
    }

}
