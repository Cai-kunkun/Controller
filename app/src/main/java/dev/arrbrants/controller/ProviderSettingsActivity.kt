package dev.arrbrants.controller

import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import dev.arrbrants.controller.data.AppPrefs
import dev.arrbrants.controller.data.SecurePrefs
import dev.arrbrants.controller.llm.DeepSeekBalance

class ProviderSettingsActivity : AutoSaveSettingsActivity() {

    private lateinit var url: TextInputEditText
    private lateinit var key: TextInputEditText
    private lateinit var model: TextInputEditText
    private lateinit var keyBalance: TextView
    private var balanceLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_provider_settings)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        url = findViewById(R.id.etUrl)
        key = findViewById(R.id.etKey)
        model = findViewById(R.id.etModel)
        keyBalance = findViewById(R.id.keyBalance)

        val prefs = AppPrefs.get(this)
        url.setText(prefs.getString(AppPrefs.KEY_URL, ""))
        key.setText(SecurePrefs.apiKey(this))
        model.setText(prefs.getString(AppPrefs.KEY_MODEL, ""))

        keyBalance.setOnClickListener { refreshBalance() }
        refreshBalance()
    }

    override fun save() {
        if (!::url.isInitialized) return
        AppPrefs.get(this).edit()
            .putString(AppPrefs.KEY_URL, url.text.toString().trim())
            .putString(AppPrefs.KEY_MODEL, model.text.toString().trim())
            .apply()
        SecurePrefs.setApiKey(this, key.text.toString().trim())
    }

    private fun refreshBalance() {
        val cfg = AppPrefs.config(this)
        if (!DeepSeekBalance.isOfficial(cfg.baseUrl) || cfg.apiKey.isBlank()) {
            keyBalance.visibility = View.GONE
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
                    keyBalance.visibility = View.GONE
                } else {
                    keyBalance.text = getString(R.string.balance_format, balance.display)
                    keyBalance.visibility = View.VISIBLE
                }
            }
        }.start()
    }
}
