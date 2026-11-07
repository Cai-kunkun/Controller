package dev.arrbrants.controller

import dev.arrbrants.controller.data.AppPrefs
import dev.arrbrants.controller.data.SecurePrefs
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import dev.arrbrants.controller.llm.DeepSeekBalance

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val prefs = AppPrefs.get(this)
        val url = findViewById<TextInputEditText>(R.id.etUrl)
        val key = findViewById<TextInputEditText>(R.id.etKey)
        val model = findViewById<TextInputEditText>(R.id.etModel)
        val contextWindow = findViewById<TextInputEditText>(R.id.etContext)
        val nativeTools = findViewById<MaterialSwitch>(R.id.switchTools)
        val keyBalance = findViewById<TextView>(R.id.keyBalance)

        url.setText(prefs.getString(AppPrefs.KEY_URL, ""))
        key.setText(SecurePrefs.apiKey(this))
        model.setText(prefs.getString(AppPrefs.KEY_MODEL, ""))
        nativeTools.isChecked = prefs.getBoolean(AppPrefs.KEY_NATIVE_TOOLS, true)
        val limit = prefs.getInt(AppPrefs.KEY_CONTEXT_WINDOW, AppPrefs.DEFAULT_CONTEXT_WINDOW)
        contextWindow.setText(limit.toString())

        keyBalance.setOnClickListener { refreshBalance(keyBalance) }
        refreshBalance(keyBalance)

        findViewById<View>(R.id.btnSave).setOnClickListener {
            val parsedLimit = contextWindow.text.toString().trim().toIntOrNull()
                ?.coerceIn(AppPrefs.MIN_CONTEXT_WINDOW, 4_000_000)
                ?: AppPrefs.DEFAULT_CONTEXT_WINDOW
            prefs.edit()
                .putString(AppPrefs.KEY_URL, url.text.toString().trim())
                .putString(AppPrefs.KEY_MODEL, model.text.toString().trim())
                .putInt(AppPrefs.KEY_CONTEXT_WINDOW, parsedLimit)
                .putBoolean(AppPrefs.KEY_NATIVE_TOOLS, nativeTools.isChecked)
                .apply()
            SecurePrefs.setApiKey(this, key.text.toString().trim())
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private var balanceLoading = false

    private fun refreshBalance(view: TextView) {
        val cfg = AppPrefs.config(this)
        if (!DeepSeekBalance.isOfficial(cfg.baseUrl) || cfg.apiKey.isBlank()) {
            view.visibility = View.GONE
            return
        }
        if (balanceLoading) return
        balanceLoading = true
        Thread {
            val balance = DeepSeekBalance.fetch(cfg.baseUrl, cfg.apiKey)
            runOnUiThread {
                balanceLoading = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (balance == null) {
                    view.visibility = View.GONE
                } else {
                    view.text = getString(R.string.balance_format, balance.display)
                    view.visibility = View.VISIBLE
                }
            }
        }.start()
    }
}
