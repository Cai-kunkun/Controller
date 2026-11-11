package dev.arrbrants.controller

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import dev.arrbrants.controller.agent.AdbBackend
import dev.arrbrants.controller.agent.Shell
import dev.arrbrants.controller.agent.ShizukuBackend
import dev.arrbrants.controller.data.AppPrefs
import rikka.shizuku.Shizuku

/** Root, Shizuku or ADB wireless debugging. */
class ExecutionSettingsActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var shizukuCard: View
    private lateinit var adbCard: View
    private lateinit var authorizeButton: MaterialButton
    private lateinit var connectButton: MaterialButton
    private var mode = "root"

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == REQUEST_SHIZUKU) updateUi()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_execution_settings)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        status = findViewById(R.id.execStatus)
        shizukuCard = findViewById(R.id.shizukuCard)
        adbCard = findViewById(R.id.adbCard)
        authorizeButton = findViewById(R.id.btnAuthorize)
        connectButton = findViewById(R.id.btnConnect)

        val rootRadio = findViewById<MaterialRadioButton>(R.id.radioRoot)
        val shizukuRadio = findViewById<MaterialRadioButton>(R.id.radioShizuku)
        val adbRadio = findViewById<MaterialRadioButton>(R.id.radioAdb)

        mode = AppPrefs.shellMode(this)
        when (mode) {
            "shizuku" -> shizukuRadio.isChecked = true
            "adb" -> adbRadio.isChecked = true
            else -> rootRadio.isChecked = true
        }
        rootRadio.setOnClickListener { setMode("root") }
        shizukuRadio.setOnClickListener { setMode("shizuku") }
        adbRadio.setOnClickListener { setMode("adb") }

        authorizeButton.setOnClickListener { requestShizuku() }
        findViewById<View>(R.id.btnPair).setOnClickListener { showPairDialog() }
        connectButton.setOnClickListener { toggleAdb() }

        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
        } catch (e: Throwable) {
            // Shizuku is not running.
        }
        updateUi()
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        } catch (e: Throwable) {
            // Shizuku is not running.
        }
        super.onDestroy()
    }

    private fun setMode(value: String) {
        mode = value
        AppPrefs.get(this).edit().putString(AppPrefs.KEY_SHELL_MODE, value).apply()
        Shell.refresh()
        updateUi()
    }

    private fun updateUi() {
        shizukuCard.visibility = if (mode == "shizuku") View.VISIBLE else View.GONE
        adbCard.visibility = if (mode == "adb") View.VISIBLE else View.GONE

        when (mode) {
            "shizuku" -> status.text = when {
                !ShizukuBackend.isAvailable() -> getString(R.string.exec_shizuku_not_running)
                ShizukuBackend.hasPermission() -> getString(R.string.exec_shizuku_granted)
                else -> getString(R.string.exec_shizuku_not_granted)
            }
            "adb" -> status.text = if (AdbBackend.isAvailable()) {
                getString(R.string.exec_adb_connected)
            } else {
                getString(R.string.exec_adb_disconnected)
            }
            else -> {
                // Probing `su` can block for seconds and may show a root prompt,
                // so never do it on the main thread.
                status.text = getString(R.string.exec_checking)
                Thread {
                    val granted = Shell.isAvailable()
                    runOnUiThread {
                        if (isFinishing || isDestroyed || mode != "root") return@runOnUiThread
                        status.text = getString(
                            if (granted) R.string.exec_root_granted
                            else R.string.exec_root_not_granted
                        )
                    }
                }.start()
            }
        }

        authorizeButton.isEnabled = ShizukuBackend.isAvailable()
        authorizeButton.text = getString(
            if (ShizukuBackend.hasPermission()) R.string.exec_shizuku_authorized
            else R.string.exec_shizuku_authorize
        )
        connectButton.text = getString(
            if (AdbBackend.isAvailable()) R.string.exec_adb_disconnect
            else R.string.exec_adb_connect
        )
    }

    private fun requestShizuku() {
        if (!ShizukuBackend.isAvailable()) {
            Toast.makeText(this, R.string.exec_shizuku_not_running, Toast.LENGTH_LONG).show()
            return
        }
        ShizukuBackend.requestPermission(REQUEST_SHIZUKU)
    }

    private fun showPairDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_adb_pair, null)
        val port = view.findViewById<TextInputEditText>(R.id.pairPort)
        val code = view.findViewById<TextInputEditText>(R.id.pairCode)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.exec_adb_pair)
            .setView(view)
            .setPositiveButton(R.string.exec_adb_pair) { _, _ ->
                val pairingPort = port.text.toString().trim().toIntOrNull()
                val pairingCode = code.text.toString().trim()
                if (pairingPort == null || pairingCode.length != 6) {
                    Toast.makeText(this, R.string.exec_adb_pair_fail, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                Toast.makeText(this, R.string.exec_adb_pairing, Toast.LENGTH_SHORT).show()
                Thread {
                    val ok = AdbBackend.pair(this, pairingPort, pairingCode)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        Toast.makeText(
                            this,
                            if (ok) R.string.exec_adb_pair_ok else R.string.exec_adb_pair_fail,
                            Toast.LENGTH_LONG
                        ).show()
                        updateUi()
                    }
                }.start()
            }
            .setNegativeButton(R.string.plugins_cancel, null)
            .show()
    }

    private fun toggleAdb() {
        if (AdbBackend.isAvailable()) {
            AdbBackend.disconnect()
            updateUi()
            return
        }
        Toast.makeText(this, R.string.exec_adb_connecting, Toast.LENGTH_SHORT).show()
        Thread {
            val ok = AdbBackend.connect(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                Toast.makeText(
                    this,
                    if (ok) R.string.exec_adb_connect_ok else R.string.exec_adb_connect_fail,
                    Toast.LENGTH_LONG
                ).show()
                updateUi()
            }
        }.start()
    }

    private companion object {
        const val REQUEST_SHIZUKU = 1001
    }
}
