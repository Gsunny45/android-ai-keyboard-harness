package dev.patrickgold.florisboard.ime.ai.providers

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted key-value store for API keys.
 *
 * Uses AES-256 GCM via EncryptedSharedPreferences (androidx.security.crypto).
 * Keys are stored under their keyRef name (e.g. "ANTHROPIC_KEY", "OPENAI_KEY").
 *
 * Thread-safe: EncryptedSharedPreferences is synchronized internally.
 */
class KeyVault(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Retrieve an API key by its keyRef. Returns null if not set. */
    fun getKey(keyRef: String): String? {
        return prefs.getString(keyRef, null)
    }

    /** Convenience alias for [getKey]. */
    fun get(keyRef: String): String? = getKey(keyRef)

    /** Store or overwrite an API key. */
    fun setKey(keyRef: String, key: String) {
        prefs.edit().putString(keyRef, key).apply()
    }

    /** Convenience alias for [setKey]. */
    fun set(keyRef: String, value: String) = setKey(keyRef, value)

    /** Returns true if the keyRef has a stored value. */
    fun hasKey(keyRef: String): Boolean {
        return prefs.contains(keyRef)
    }

    /** Remove a stored key. */
    fun clearKey(keyRef: String) {
        prefs.edit().remove(keyRef).apply()
    }

    /** Remove ALL stored keys. */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    /** All keyRefs that have stored values. */
    fun storedKeyRefs(): Set<String> {
        return prefs.all.keys
    }

    companion object {
        private const val PREFS_NAME = "florisboard_ai_keyvault"

        @Volatile
        private var instance: KeyVault? = null

        /**
         * Singleton accessor. Pass any app Context; the singleton holds
         * only the applicationContext internally.
         */
        fun getInstance(context: Context): KeyVault {
            return instance ?: synchronized(this) {
                instance ?: KeyVault(context.applicationContext).also { instance = it }
            }
        }
    }
}
