package dev.arrbrants.controller

import android.os.Bundle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import dev.arrbrants.controller.data.AppPrefs

class ContextSettingsActivity : AutoSaveSettingsActivity() {

    private lateinit var etContext: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_context_settings)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        etContext = findViewById(R.id.etContext)
        etContext.setText(AppPrefs.contextWindow(this).toString())
    }

    override fun save() {
        if (!::etContext.isInitialized) return
        val value = etContext.text.toString().trim().toIntOrNull()
            ?.coerceIn(AppPrefs.MIN_CONTEXT_WINDOW, 4_000_000)
            ?: return
        AppPrefs.get(this).edit().putInt(AppPrefs.KEY_CONTEXT_WINDOW, value).apply()
    }
}
