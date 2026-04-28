package dev.patrickgold.florisboard.ime.ai.providers

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted key-value store for API keys.
 *
 * Uses AES-256 GCM via EncryptedSharedPreferences (androidx.security.crypto).
 * Keys are stored under their keyRef name (e.g. "ANTHROPIC_KEY", "GROQ_KEY").
 *
 * ## Boot-before-unlock safety
 * EncryptedSharedPreferences lives in credential-encrypted storage, which is
 * unavailable until the user has unlocked the device at least once after boot.
 * If [get] / [set] are called before unlock, they return null / no-op rather
 * than crashing. The prefs are opened lazily on the first call that arrives
 * after the device is unlocked.
 *
 * Thread-safe: EncryptedSharedPreferences is synchronized internally;
 * the lazy init is guarded by [prefsLock].
 */
class KeyVault private constructor(private val appContext: Context) {

    private val prefsLock = Any()

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * Returns the SharedPreferences, opening them lazily.
     * Returns null if the device is not yet unlocked (boot state).
     */
    private fun getPrefs(): SharedPreferences? {
        prefs?.let { return it }
        return synchronized(prefsLock) {
            prefs ?: tryOpenPrefs()?.also { prefs = it }
        }
    }

    private fun tryOpenPrefs(): SharedPreferences? {
        // Guard: credential-encrypted storage requires user unlock
        val userManager = appContext.getSystemService(Context.USER_SERVICE) as? UserManager
        if (userManager?.isUserUnlocked == false) {
            Log.w(TAG, "Device not yet unlocked — KeyVault deferred until unlock")
            return null
        }
        return try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open EncryptedSharedPreferences", e)
            null
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    /** Retrieve an API key by its keyRef. Returns null if not set or device locked. */
    fun getKey(keyRef: String): String? = getPrefs()?.getString(keyRef, null)

    /** Convenience alias for [getKey]. */
    fun get(keyRef: String): String? = getKey(keyRef)

    /**
     * Store or overwrite an API key.
     * No-op (logs warning) if the device is not yet unlocked.
     */
    fun setKey(keyRef: String, key: String) {
        val p = getPrefs()
        if (p == null) { Log.w(TAG, "setKey($keyRef): device locked, skipping"); return }
        p.edit().putString(keyRef, key).apply()
    }

    /** Convenience alias for [setKey]. */
    fun set(keyRef: String, value: String) = setKey(keyRef, value)

    /** Returns true if the keyRef has a stored value. Returns false if device locked. */
    fun hasKey(keyRef: String): Boolean = getPrefs()?.contains(keyRef) ?: false

    /** Remove a stored key. No-op if device locked. */
    fun clearKey(keyRef: String) {
        getPrefs()?.edit()?.remove(keyRef)?.apply()
    }

    /** Remove ALL stored keys. No-op if device locked. */
    fun clearAll() {
        getPrefs()?.edit()?.clear()?.apply()
    }

    /** All keyRefs that have stored values. Empty set if device locked. */
    fun storedKeyRefs(): Set<String> = getPrefs()?.all?.keys ?: emptySet()

    /**
     * Call after the user unlocks the device (e.g. from ACTION_USER_UNLOCKED
     * broadcast) to force the lazy prefs open immediately.
     * Safe to call multiple times.
     */
    fun onUserUnlocked() {
        if (prefs == null) {
            getPrefs()
            Log.i(TAG, "KeyVault opened after user unlock")
        }
    }

    companion object {
        private const val TAG = "KeyVault"
        private const val PREFS_NAME = "florisboard_ai_keyvault"

        @Volatile
        private var instance: KeyVault? = null

        /**
         * Singleton accessor. Safe to call at any time including before unlock —
         * actual prefs access is deferred until the device is unlocked.
         */
        fun getInstance(context: Context): KeyVault {
            return instance ?: synchronized(this) {
                instance ?: KeyVault(context.applicationContext).also { instance = it }
            }
        }
    }
}
