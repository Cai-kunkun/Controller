package dev.arrbrants.controller

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the API key in Keystore-backed [EncryptedSharedPreferences].
 *
 * Older builds kept it as plain text in the regular preferences file; the
 * first read migrates that value into the encrypted file and deletes the
 * plaintext copy.
 */
object SecurePrefs {

    private const val FILE = "secure_prefs"
    private const val KEY_API = "api_key"
    private const val TAG = "SecurePrefs"

    @Volatile
    private var cached: SharedPreferences? = null

    private fun encrypted(context: Context): SharedPreferences? {
        cached?.let { return it }
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            cached = prefs
            prefs
        } catch (e: Exception) {
            // Keystore not available (rare). Callers fall back to the legacy
            // store so the app stays usable, but nothing is silently migrated.
            Log.e(TAG, "Encrypted preferences unavailable", e)
            null
        }
    }

    private fun legacy(context: Context): SharedPreferences =
        context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)

    fun apiKey(context: Context): String {
        val app = context.applicationContext
        val stored = encrypted(app)?.getString(KEY_API, null)
        if (!stored.isNullOrEmpty()) return stored

        val old = legacy(app).getString(KEY_API, null)
        if (!old.isNullOrEmpty()) {
            setApiKey(app, old)
            return old
        }
        return ""
    }

    fun setApiKey(context: Context, value: String) {
        val app = context.applicationContext
        val encryptedPrefs = encrypted(app)
        if (encryptedPrefs != null) {
            encryptedPrefs.edit().putString(KEY_API, value).apply()
            // Drop any plaintext copy left by an older build.
            legacy(app).edit().remove(KEY_API).apply()
        } else {
            legacy(app).edit().putString(KEY_API, value).apply()
        }
    }
}
