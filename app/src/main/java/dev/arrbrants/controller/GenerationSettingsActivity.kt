package dev.arrbrants.controller

import android.os.Bundle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import dev.arrbrants.controller.data.AppPrefs

class GenerationSettingsActivity : AutoSaveSettingsActivity() {

    private lateinit var thinking: MaterialAutoCompleteTextView
    private lateinit var nativeTools: MaterialSwitch
    private lateinit var thinkingLabels: Array<String>
    private lateinit var thinkingValues: Array<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_generation_settings)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        thinking = findViewById(R.id.etThinking)
        nativeTools = findViewById(R.id.switchTools)

        val prefs = AppPrefs.get(this)
        thinkingLabels = arrayOf(
            getString(R.string.thinking_default),
            getString(R.string.thinking_none),
            getString(R.string.thinking_low),
            getString(R.string.thinking_high),
            getString(R.string.thinking_max)
        )
        thinkingValues = arrayOf("", "none", "low", "high", "max")
        thinking.setSimpleItems(thinkingLabels)
        val current = thinkingValues.indexOf(prefs.getString(AppPrefs.KEY_REASONING, "") ?: "")
        thinking.setText(thinkingLabels[if (current >= 0) current else 0], false)
        nativeTools.isChecked = prefs.getBoolean(AppPrefs.KEY_NATIVE_TOOLS, true)
    }

    override fun save() {
        if (!::thinking.isInitialized) return
        val index = thinkingLabels.indexOf(thinking.text.toString()).let { if (it >= 0) it else 0 }
        AppPrefs.get(this).edit()
            .putString(AppPrefs.KEY_REASONING, thinkingValues[index])
            .putBoolean(AppPrefs.KEY_NATIVE_TOOLS, nativeTools.isChecked)
            .apply()
    }
}
