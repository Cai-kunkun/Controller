package dev.arrbrants.controller

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        val url = findViewById<TextInputEditText>(R.id.etUrl)
        val key = findViewById<TextInputEditText>(R.id.etKey)
        val model = findViewById<TextInputEditText>(R.id.etModel)
        val contextWindow = findViewById<TextInputEditText>(R.id.etContext)
        val nativeTools = findViewById<MaterialSwitch>(R.id.switchTools)

        url.setText(prefs.getString(MainActivity.KEY_URL, ""))
        key.setText(SecurePrefs.apiKey(this))
        model.setText(prefs.getString(MainActivity.KEY_MODEL, ""))
        nativeTools.isChecked = prefs.getBoolean(MainActivity.KEY_NATIVE_TOOLS, true)
        val limit = prefs.getInt(MainActivity.KEY_CONTEXT_WINDOW, MainActivity.DEFAULT_CONTEXT_WINDOW)
        contextWindow.setText(limit.toString())

        findViewById<View>(R.id.btnSave).setOnClickListener {
            val parsedLimit = contextWindow.text.toString().trim().toIntOrNull()
                ?.coerceIn(8_192, 4_000_000)
                ?: MainActivity.DEFAULT_CONTEXT_WINDOW
            prefs.edit()
                .putString(MainActivity.KEY_URL, url.text.toString().trim())
                .putString(MainActivity.KEY_MODEL, model.text.toString().trim())
                .putInt(MainActivity.KEY_CONTEXT_WINDOW, parsedLimit)
                .putBoolean(MainActivity.KEY_NATIVE_TOOLS, nativeTools.isChecked)
                .apply()
            SecurePrefs.setApiKey(this, key.text.toString().trim())
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
