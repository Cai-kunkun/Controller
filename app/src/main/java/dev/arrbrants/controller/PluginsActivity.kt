package dev.arrbrants.controller

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.arrbrants.controller.data.Plugin
import dev.arrbrants.controller.data.PluginStore
import dev.arrbrants.controller.ui.PluginAdapter

/** Import, enable and delete declarative plugins. */
class PluginsActivity : AppCompatActivity() {

    private var plugins: List<Plugin> = emptyList()
    private lateinit var adapter: PluginAdapter
    private lateinit var emptyText: TextView

    private val importer =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importPlugin(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plugins)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        emptyText = findViewById(R.id.emptyText)
        val list = findViewById<RecyclerView>(R.id.list)
        adapter = PluginAdapter(
            { plugins },
            onToggle = ::requestToggle,
            onDelete = ::confirmDelete
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<View>(R.id.btnImport).setOnClickListener {
            importer.launch(arrayOf("*/*"))
        }
        reload()
    }

    private fun reload() {
        plugins = PluginStore.loadAll(this)
        adapter.notifyDataSetChanged()
        emptyText.visibility = if (plugins.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun importPlugin(uri: Uri) {
        Thread {
            val imported = PluginStore.importUri(this, uri)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (imported == null) {
                    Toast.makeText(this, R.string.plugins_import_failed, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.plugins_import_ok, imported.name),
                        Toast.LENGTH_LONG
                    ).show()
                }
                reload()
            }
        }.start()
    }

    private fun requestToggle(plugin: Plugin, enabled: Boolean) {
        if (!enabled) {
            PluginStore.setEnabled(this, plugin, false)
            reload()
            return
        }
        val dangerous = plugin.steps.any { it.type == "shell" || it.type == "http" }
        if (!dangerous) {
            PluginStore.setEnabled(this, plugin, true)
            reload()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.plugins_enable_danger_title)
            .setMessage(getString(R.string.plugins_enable_danger, plugin.name))
            .setPositiveButton(R.string.plugins_enable) { _, _ ->
                PluginStore.setEnabled(this, plugin, true)
                reload()
            }
            .setNegativeButton(R.string.plugins_cancel) { _, _ -> reload() }
            .show()
    }

    private fun confirmDelete(plugin: Plugin) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.plugins_delete_title)
            .setMessage(getString(R.string.plugins_delete_message, plugin.name))
            .setPositiveButton(R.string.plugins_delete) { _, _ ->
                PluginStore.delete(this, plugin.id)
                reload()
            }
            .setNegativeButton(R.string.plugins_cancel) { _, _ -> }
            .show()
    }
}
