package com.dev.docscannerpdf.domain.security

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

data class AppLockSettings(
    val pinCreated: Boolean = false,
    val lockEnabled: Boolean = false,
    val biometricsEnabled: Boolean = false
)

class AppLockRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()

    fun getSettings(): AppLockSettings {
        val pinHash = preferences.getString(KEY_PIN_HASH, null)
        return AppLockSettings(
            pinCreated = pinHash != null,
            lockEnabled = preferences.getBoolean(KEY_LOCK_ENABLED, false) && pinHash != null,
            biometricsEnabled = preferences.getBoolean(KEY_BIOMETRICS_ENABLED, false) && pinHash != null
        )
    }

    fun savePin(pin: String) {
        val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
        val hash = hashPin(pin, salt)
        preferences.edit()
            .putString(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_PIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .putBoolean(KEY_LOCK_ENABLED, true)
            .apply()
    }

    fun verifyPin(pin: String): Boolean {
        val saltValue = preferences.getString(KEY_PIN_SALT, null) ?: return false
        val hashValue = preferences.getString(KEY_PIN_HASH, null) ?: return false
        val salt = runCatching { Base64.decode(saltValue, Base64.NO_WRAP) }.getOrNull() ?: return false
        val expectedHash = runCatching { Base64.decode(hashValue, Base64.NO_WRAP) }.getOrNull() ?: return false
        return MessageDigest.isEqual(hashPin(pin, salt), expectedHash)
    }

    fun setLockEnabled(enabled: Boolean) {
        if (getSettings().pinCreated) {
            preferences.edit().putBoolean(KEY_LOCK_ENABLED, enabled).apply()
        }
    }

    fun setBiometricsEnabled(enabled: Boolean) {
        if (getSettings().pinCreated) {
            preferences.edit().putBoolean(KEY_BIOMETRICS_ENABLED, enabled).apply()
        }
    }

    fun clearLock() {
        preferences.edit()
            .remove(KEY_PIN_SALT)
            .remove(KEY_PIN_HASH)
            .putBoolean(KEY_LOCK_ENABLED, false)
            .putBoolean(KEY_BIOMETRICS_ENABLED, false)
            .apply()
    }

    private fun hashPin(pin: String, salt: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(salt + pin.toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val PREFS_NAME = "app_lock"
        const val KEY_PIN_SALT = "pin_salt"
        const val KEY_PIN_HASH = "pin_hash"
        const val KEY_LOCK_ENABLED = "lock_enabled"
        const val KEY_BIOMETRICS_ENABLED = "biometrics_enabled"
        const val SALT_BYTES = 16
    }
}
