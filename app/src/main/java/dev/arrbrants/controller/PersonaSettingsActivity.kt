package dev.arrbrants.controller

import android.os.Bundle
import android.widget.EditText
import com.google.android.material.appbar.MaterialToolbar
import dev.arrbrants.controller.data.AppPrefs

class PersonaSettingsActivity : AutoSaveSettingsActivity() {

    private lateinit var persona: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_persona_settings)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        persona = findViewById(R.id.etPersona)
        persona.setText(AppPrefs.persona(this))
        persona.setSelection(persona.text.length)
    }

    override fun save() {
        if (!::persona.isInitialized) return
        AppPrefs.get(this).edit()
            .putString(AppPrefs.KEY_PERSONA, persona.text.toString().trim())
            .apply()
    }
}
