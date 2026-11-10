package dev.arrbrants.controller

import dev.arrbrants.controller.data.AppPrefs
import dev.arrbrants.controller.data.SecurePrefs
import dev.arrbrants.controller.data.UpdateChecker
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
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
        val thinking = findViewById<MaterialAutoCompleteTextView>(R.id.etThinking)
        val persona = findViewById<TextInputEditText>(R.id.etPersona)
        val aboutVersion = findViewById<TextView>(R.id.aboutVersion)

        url.setText(prefs.getString(AppPrefs.KEY_URL, ""))
        key.setText(SecurePrefs.apiKey(this))
        model.setText(prefs.getString(AppPrefs.KEY_MODEL, ""))
        persona.setText(AppPrefs.persona(this))
        nativeTools.isChecked = prefs.getBoolean(AppPrefs.KEY_NATIVE_TOOLS, true)

        val thinkingLabels = arrayOf(
            getString(R.string.thinking_default),
            getString(R.string.thinking_none),
            getString(R.string.thinking_low),
            getString(R.string.thinking_high),
            getString(R.string.thinking_max)
        )
        val thinkingValues = arrayOf("", "none", "low", "high", "max")
        thinking.setSimpleItems(thinkingLabels)
        val currentThinking =
            thinkingValues.indexOf(prefs.getString(AppPrefs.KEY_REASONING, "") ?: "")
        thinking.setText(
            thinkingLabels[if (currentThinking >= 0) currentThinking else 0],
            false
        )

        val limit = prefs.getInt(AppPrefs.KEY_CONTEXT_WINDOW, AppPrefs.DEFAULT_CONTEXT_WINDOW)
        contextWindow.setText(limit.toString())

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        aboutVersion.text = getString(R.string.about_version, version)
        findViewById<View>(R.id.btnCheckUpdate).setOnClickListener {
            checkForUpdates(version)
        }

        keyBalance.setOnClickListener { refreshBalance(keyBalance) }
        refreshBalance(keyBalance)

        findViewById<View>(R.id.btnPlugins).setOnClickListener {
            startActivity(Intent(this, PluginsActivity::class.java))
        }

        findViewById<View>(R.id.btnSave).setOnClickListener {
            val parsedLimit = contextWindow.text.toString().trim().toIntOrNull()
                ?.coerceIn(AppPrefs.MIN_CONTEXT_WINDOW, 4_000_000)
                ?: AppPrefs.DEFAULT_CONTEXT_WINDOW
            val thinkingIndex = thinkingLabels.indexOf(thinking.text.toString())
                .let { if (it >= 0) it else 0 }
            prefs.edit()
                .putString(AppPrefs.KEY_URL, url.text.toString().trim())
                .putString(AppPrefs.KEY_MODEL, model.text.toString().trim())
                .putInt(AppPrefs.KEY_CONTEXT_WINDOW, parsedLimit)
                .putBoolean(AppPrefs.KEY_NATIVE_TOOLS, nativeTools.isChecked)
                .putString(AppPrefs.KEY_REASONING, thinkingValues[thinkingIndex])
                .putString(AppPrefs.KEY_PERSONA, persona.text.toString().trim())
                .apply()
            SecurePrefs.setApiKey(this, key.text.toString().trim())
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private var balanceLoading = false

    private fun checkForUpdates(currentVersion: String) {
        val button = findViewById<View>(R.id.btnCheckUpdate)
        button.isEnabled = false
        Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
        Thread {
            val result = UpdateChecker.check(currentVersion)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                button.isEnabled = true
                when (result) {
                    is UpdateChecker.Result.Available -> showUpdate(result.info)
                    UpdateChecker.Result.Latest ->
                        Toast.makeText(this, R.string.update_latest, Toast.LENGTH_SHORT).show()
                    UpdateChecker.Result.Error ->
                        Toast.makeText(this, R.string.update_error, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showUpdate(info: UpdateChecker.Info) {
        val message = if (info.notes.isBlank()) {
            getString(R.string.update_available_title, info.version)
        } else {
            info.notes.take(1200)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_available_title, info.version))
            .setMessage(message)
            .setPositiveButton(R.string.update_open) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.url)))
                } catch (e: Exception) {
                    // No browser installed.
                }
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

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
