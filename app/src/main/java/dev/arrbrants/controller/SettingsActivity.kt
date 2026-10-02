package dev.arrbrants.controller

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
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

        url.setText(prefs.getString(MainActivity.KEY_URL, ""))
        key.setText(prefs.getString(MainActivity.KEY_API, ""))
        model.setText(prefs.getString(MainActivity.KEY_MODEL, ""))

        findViewById<View>(R.id.btnSave).setOnClickListener {
            prefs.edit()
                .putString(MainActivity.KEY_URL, url.text.toString().trim())
                .putString(MainActivity.KEY_API, key.text.toString().trim())
                .putString(MainActivity.KEY_MODEL, model.text.toString().trim())
                .apply()
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
