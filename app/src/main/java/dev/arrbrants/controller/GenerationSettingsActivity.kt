package dev.arrbrants.controller

import android.os.Bundle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import dev.arrbrants.controller.data.AppPrefs

class GenerationSettingsActivity : AutoSaveSettingsActivity() {

    private lateinit var nativeTools: MaterialSwitch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_generation_settings)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        nativeTools = findViewById(R.id.switchTools)
        nativeTools.isChecked = AppPrefs.nativeTools(this)
    }

    override fun save() {
        if (!::nativeTools.isInitialized) return
        AppPrefs.get(this).edit()
            .putBoolean(AppPrefs.KEY_NATIVE_TOOLS, nativeTools.isChecked)
            .apply()
    }
}
