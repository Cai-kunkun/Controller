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
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.Gravity
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "llm_prefs"
        const val KEY_URL = "base_url"
        const val KEY_API = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_CURRENT = "current_id"
        const val KEY_AGENT = "agent_mode"
        const val TITLE_MAX = 24
    }

    private var history: MutableList<ChatMessage> = ArrayList()
    private var conversations: MutableList<ConversationStore.Conversation> = ArrayList()
    private var currentId = -1L
    private var agentMode = false

    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: ChatAdapter
    private lateinit var drawerAdapter: ConversationAdapter
    private lateinit var list: RecyclerView
    private lateinit var drawerList: RecyclerView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var input: TextInputEditText
    private lateinit var inputLayout: TextInputLayout
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
        agentMode = prefs.getBoolean(KEY_AGENT, false)
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
        inputLayout = findViewById(R.id.inputLayout)
        adapter = ChatAdapter()
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        drawerAdapter = ConversationAdapter()
        drawerList.layoutManager = LinearLayoutManager(this)
        drawerList.adapter = drawerAdapter

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
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
        updateInputHint()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_agent)?.isChecked = agentMode
        menu.findItem(R.id.action_stop)?.isVisible = AgentService.running
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_agent -> {
                agentMode = !agentMode
                prefs.edit().putBoolean(KEY_AGENT, agentMode).apply()
                updateInputHint()
                invalidateOptionsMenu()
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
        refreshUi()
        refreshDrawer()
    }

    private fun openConversation(conv: ConversationStore.Conversation) {
        drawerLayout.closeDrawer(Gravity.START)
        currentId = conv.id
        history = conv.messages
        refreshUi()
        refreshDrawer()
    }

    private fun deleteConversation(conv: ConversationStore.Conversation) {
        conversations.remove(conv)
        ScreenCapture.deleteShots(this, conv.messages)
        if (currentId == conv.id) {
            history = ArrayList()
            currentId = -1L
            refreshUi()
        }
        ConversationStore.saveAll(prefs, conversations)
        refreshDrawer()
    }

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
        refreshUi()
        refreshDrawer()

        if (agentMode) {
            ensureNotificationPermission()
            AgentService.start(this, text, currentId)
            invalidateOptionsMenu()
            moveTaskToBack(true)
            return
        }

        busy = true
        Thread {
            val reply = try {
                LlmClient.send(cfg, history.map { LlmClient.Msg(it.role, it.content) })
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
            runOnUiThread {
                history.add(ChatMessage("assistant", reply))
                busy = false
                syncCurrent()
                refreshUi()
                refreshDrawer()
            }
        }.start()
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun updateInputHint() {
        inputLayout.hint = getString(if (agentMode) R.string.agent_hint else R.string.input_hint)
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
        if (history.isNotEmpty()) {
            list.scrollToPosition(history.size - 1)
        }
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
            holder.title.text = conv.title
            holder.count.text = getString(R.string.msg_count, conv.messages.size)
            holder.itemView.setOnClickListener { openConversation(conv) }
            holder.delete.setOnClickListener { deleteConversation(conv) }
        }
    }

    private class ConversationViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.convTitle)
        val count: TextView = view.findViewById(R.id.convCount)
        val delete: TextView = view.findViewById(R.id.convDelete)
    }
}
