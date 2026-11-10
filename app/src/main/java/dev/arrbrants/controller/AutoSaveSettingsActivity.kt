package dev.arrbrants.controller

import androidx.appcompat.app.AppCompatActivity

/** Settings screens persist their fields when the user leaves; no Save button. */
abstract class AutoSaveSettingsActivity : AppCompatActivity() {

    /** Write the current fields into prefs. Called from onPause. */
    protected abstract fun save()

    override fun onPause() {
        save()
        super.onPause()
    }
}
