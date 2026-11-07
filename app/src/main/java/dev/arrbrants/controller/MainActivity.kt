package dev.arrbrants.controller

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "llm_prefs"
        const val KEY_URL = "base_url"
        const val KEY_API = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_CONTEXT_WINDOW = "context_window"
        const val KEY_CURRENT = "current_id"
        const val DEFAULT_CONTEXT_WINDOW = 128_000
        const val TITLE_MAX = 24
        /** Text-only context budget for the routing request, in tokens. */
        private const val CHAT_CONTEXT_TOKENS = 12_000L
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
    private var busy = false

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

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        conversations = ConversationStore.loadAll(prefs)
        currentId = prefs.getLong(KEY_CURRENT, -1L)
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
        adapter = ChatAdapter()
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        drawerAdapter = ConversationAdapter()
        drawerList.layoutManager = LinearLayoutManager(this)
        drawerList.adapter = drawerAdapter

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { drawerLayout.openDrawer(Gravity.START) }

        findViewById<View>(R.id.btnNewChat).setOnClickListener { newChat() }

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
        invalidateOptionsMenu()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(liveRefresh)
        // While the agent runs, the service owns the store; don't overwrite it.
        if (!AgentService.running) {
            ConversationStore.saveAll(prefs, conversations)
        }
        prefs.edit().putLong(KEY_CURRENT, currentId).apply()
    }

    private fun reloadFromPrefs() {
        conversations = ConversationStore.loadAll(prefs)
        currentId = prefs.getLong(KEY_CURRENT, currentId)
        val current = conversations.find { it.id == currentId }
        history = current?.messages ?: ArrayList()
        if (current == null) currentId = -1L
        refreshUi()
        refreshDrawer()
    }

    private fun newChat() {
        drawerLayout.closeDrawer(Gravity.START)
        history = ArrayList()
        currentId = -1L
        prefs.edit().putLong(KEY_CURRENT, currentId).apply()
        refreshUi()
        refreshDrawer()
    }

    private fun openConversation(conv: ConversationStore.Conversation) {
        drawerLayout.closeDrawer(Gravity.START)
        currentId = conv.id
        history = conv.messages
        prefs.edit().putLong(KEY_CURRENT, currentId).apply()
        refreshUi()
        refreshDrawer()
    }

    private fun deleteConversation(conv: ConversationStore.Conversation) {
        conversations.remove(conv)
        ScreenCapture.deleteShots(this, conv.messages)
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
        if (text.isEmpty()) return

        val cfg = LlmClient.Config(
            prefs.getString(KEY_URL, "") ?: "",
            prefs.getString(KEY_API, "") ?: "",
            prefs.getString(KEY_MODEL, "") ?: ""
        )
        if (cfg.baseUrl.isBlank()) {
            Toast.makeText(this, R.string.need_setup, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        input.setText("")
        history.add(ChatMessage("user", text))
        syncCurrent()
        val conversationId = currentId
        val requestMessages = buildChatMessages()

        history.add(ChatMessage("assistant", ""))
        val index = history.size - 1
        adapter.notifyItemInserted(index)
        list.scrollToPosition(index)
        busy = true

        Thread {
            val builder = StringBuilder()
            var decided = false
            var plainText = false
            var lastPost = 0L
            var error: Exception? = null

            val reply = try {
                LlmClient.stream(cfg, requestMessages) { delta ->
                    builder.append(delta)
                    if (!decided) {
                        val start = builder.toString().trimStart()
                        if (start.isNotEmpty()) {
                            decided = true
                            plainText = !start.startsWith("{") && !start.startsWith("```")
                        }
                    }
                    val now = System.currentTimeMillis()
                    if (decided && plainText && now - lastPost >= 60) {
                        lastPost = now
                        val snapshot = builder.toString()
                        runOnUiThread { updateBubble(index, snapshot) }
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
        val step = AgentProtocol.asActionOrNull(reply.text)
        when {
            step == null -> updateBubble(index, reply.text)

            step.action is AgentProtocol.Action.Done ->
                updateBubble(index, getString(R.string.agent_done, step.action.summary))

            step.action is AgentProtocol.Action.Ask ->
                updateBubble(index, getString(R.string.agent_ask, step.action.question))

            else -> {
                // The model wants to operate the phone: show a note, then let
                // the service take over with screenshots and real actions.
                updateBubble(index, getString(R.string.agent_auto_start))
                ensureNotificationPermission()
                AgentService.start(this, conversationId)
                invalidateOptionsMenu()
                moveTaskToBack(true)
            }
        }
    }

    /** Updates one bubble in place while a reply streams in. */
    private fun updateBubble(index: Int, text: String) {
        if (isFinishing || isDestroyed) return
        if (index !in history.indices) return
        history[index] = history[index].copy(content = text)
        adapter.notifyItemChanged(index)
        list.scrollToPosition(index)
    }

    /** Text-only context for the routing/chat request, newest messages first. */
    private fun buildChatMessages(): List<LlmClient.Msg> {
        val selected = ArrayList<LlmClient.Msg>()
        var tokens = 0L
        for (m in history.asReversed()) {
            if (m.content.isBlank()) continue
            val msg = LlmClient.Msg(m.role, m.content)
            if (tokens + msg.approxTokens > CHAT_CONTEXT_TOKENS) break
            selected.add(0, msg)
            tokens += msg.approxTokens
        }
        return ArrayList<LlmClient.Msg>(selected.size + 1).apply {
            add(LlmClient.Msg("system", AgentProtocol.systemPrompt()))
            addAll(selected)
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /** Registers a fresh conversation on the first message and persists state. */
    private fun syncCurrent() {
        if (currentId == -1L) {
            val title = history.firstOrNull { it.role == "user" }
                ?.content?.take(TITLE_MAX)
                ?: getString(R.string.new_chat)
            val conv = ConversationStore.Conversation(
                System.currentTimeMillis(), title, history
            )
            conversations.add(0, conv)
            currentId = conv.id
        }
        ConversationStore.saveAll(prefs, conversations)
        prefs.edit().putLong(KEY_CURRENT, currentId).apply()
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
        val model = prefs.getString(KEY_MODEL, "")?.trim().orEmpty()
        toolbar.subtitle = if (model.isEmpty()) getString(R.string.app_subtitle) else model
    }

    private fun refreshStats() {
        val stats = StatsStore.load(prefs, currentId)
        val limit = prefs.getInt(KEY_CONTEXT_WINDOW, DEFAULT_CONTEXT_WINDOW)
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

    private inner class ChatAdapter : RecyclerView.Adapter<BubbleViewHolder>() {

        override fun getItemCount(): Int = history.size

        override fun getItemViewType(position: Int): Int =
            if (history[position].role == "user") 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BubbleViewHolder {
            val layout = if (viewType == 0) R.layout.item_message_user else R.layout.item_message_assistant
            val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
            return BubbleViewHolder(v)
        }

        override fun onBindViewHolder(holder: BubbleViewHolder, position: Int) {
            val msg = history[position]
            holder.text.text = msg.content
            val bitmap: Bitmap? = msg.imagePath?.let { ShotThumbs.get(holder.itemView.context, it) }
            if (bitmap != null) {
                holder.thumb.visibility = View.VISIBLE
                holder.thumb.setImageBitmap(bitmap)
            } else {
                holder.thumb.visibility = View.GONE
                holder.thumb.setImageDrawable(null)
            }
        }
    }

    private class BubbleViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.content)
        val thumb: ImageView = view.findViewById(R.id.thumb)
    }

    private inner class ConversationAdapter : RecyclerView.Adapter<ConversationViewHolder>() {

        override fun getItemCount(): Int = conversations.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationViewHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_conversation, parent, false)
            return ConversationViewHolder(v)
        }

        override fun onBindViewHolder(holder: ConversationViewHolder, position: Int) {
            val conv = conversations[position]
            val selected = conv.id == currentId
            holder.title.text = conv.title
            holder.count.text = getString(R.string.msg_count, conv.messages.size)
            holder.card.setCardBackgroundColor(
                MaterialColors.getColor(
                    holder.itemView,
                    if (selected) com.google.android.material.R.attr.colorPrimaryContainer
                    else com.google.android.material.R.attr.colorSurfaceVariant
                )
            )
            holder.title.setTextColor(
                MaterialColors.getColor(
                    holder.itemView,
                    if (selected) com.google.android.material.R.attr.colorOnPrimaryContainer
                    else com.google.android.material.R.attr.colorOnSurface
                )
            )
            holder.itemView.setOnClickListener { openConversation(conv) }
            holder.delete.setOnClickListener { deleteConversation(conv) }
        }
    }

    private class ConversationViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.convCard)
        val title: TextView = view.findViewById(R.id.convTitle)
        val count: TextView = view.findViewById(R.id.convCount)
        val delete: View = view.findViewById(R.id.convDelete)
    }
}
