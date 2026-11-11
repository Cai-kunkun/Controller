package dev.arrbrants.controller

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import dev.arrbrants.controller.data.AppPrefs
import dev.arrbrants.controller.data.PluginStore
import dev.arrbrants.controller.llm.TokenBudget

/** Category list; each row opens a dedicated, auto-saving settings screen. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var categoryList: LinearLayout
    private lateinit var versionFooter: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        categoryList = findViewById(R.id.categoryList)
        versionFooter = findViewById(R.id.versionFooter)
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    private fun rebuild() {
        categoryList.removeAllViews()
        add(R.string.settings_provider, providerSummary(), ProviderSettingsActivity::class.java)
        add(R.string.settings_generation, generationSummary(), GenerationSettingsActivity::class.java)
        add(R.string.settings_context, contextSummary(), ContextSettingsActivity::class.java)
        add(R.string.settings_persona, personaSummary(), PersonaSettingsActivity::class.java)
        add(R.string.settings_execution, executionSummary(), ExecutionSettingsActivity::class.java)
        add(R.string.settings_plugins, pluginsSummary(), PluginsActivity::class.java)
        add(R.string.settings_about, aboutSummary(), AboutSettingsActivity::class.java)
        versionFooter.text = "Controller ${versionName()}"
    }

    private fun add(titleRes: Int, summary: String, activity: Class<*>) {
        val row = layoutInflater.inflate(R.layout.item_settings_category, categoryList, false)
        row.findViewById<TextView>(R.id.categoryTitle).text = getString(titleRes)
        row.findViewById<TextView>(R.id.categorySummary).text = summary
        row.setOnClickListener { startActivity(Intent(this, activity)) }
        categoryList.addView(row)
    }

    private fun providerSummary(): String {
        val model = AppPrefs.get(this).getString(AppPrefs.KEY_MODEL, "").orEmpty()
        return model.ifBlank { getString(R.string.settings_summary_unset) }
    }

    private fun generationSummary(): String = getString(
        if (AppPrefs.nativeTools(this)) {
            R.string.settings_summary_native_on
        } else {
            R.string.settings_summary_native_off
        }
    )

    private fun contextSummary(): String = getString(
        R.string.settings_summary_context,
        TokenBudget.format(AppPrefs.contextWindow(this).toLong())
    )

    private fun personaSummary(): String = if (AppPrefs.persona(this).isBlank()) {
        getString(R.string.settings_summary_default)
    } else {
        getString(R.string.settings_summary_custom)
    }

    private fun executionSummary(): String = getString(
        when (AppPrefs.shellMode(this)) {
            "shizuku" -> R.string.execution_shizuku
            "adb" -> R.string.execution_adb
            else -> R.string.execution_root
        }
    )

    private fun pluginsSummary(): String = getString(
        R.string.settings_summary_plugins,
        PluginStore.enabled(this).size
    )

    private fun aboutSummary(): String =
        getString(R.string.about_version, versionName())

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }
}
