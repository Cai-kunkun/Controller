package dev.arrbrants.controller

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.arrbrants.controller.data.UpdateChecker

class AboutSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about_settings)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        findViewById<TextView>(R.id.aboutVersion).text =
            getString(R.string.about_version, version)
        findViewById<View>(R.id.btnCheckUpdate).setOnClickListener {
            checkForUpdates(version)
        }
    }

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
}
