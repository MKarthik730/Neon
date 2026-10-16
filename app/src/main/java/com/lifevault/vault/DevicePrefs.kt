package com.lifevault.vault

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.lifevault.domain.model.AppThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Small, non-sensitive, device-local settings kept in app-private storage (excluded from backup):
 * which folder is the vault, failed-attempt counters, the biometric-wrapped key (ciphertext under a
 * hardware Keystore key, useless off this device) and the notification quick-action preference.
 * No vault content ever lives here.
 */
class DevicePrefs(context: Context) : AttemptStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("device", Context.MODE_PRIVATE)
    private val _pinLocked = MutableStateFlow(prefs.getBoolean(K_PIN_LOCKED, false))
    val pinLocked = _pinLocked.asStateFlow()

    private val _themeMode = MutableStateFlow(
        prefs.getString(K_THEME_MODE, AppThemeMode.SYSTEM.name)
            ?.let { runCatching { AppThemeMode.valueOf(it) }.getOrNull() } ?: AppThemeMode.SYSTEM,
    )
    val themeMode = _themeMode.asStateFlow()

    var treeUri: String?
        get() = prefs.getString(K_TREE, null)
        set(v) = prefs.edit { putString(K_TREE, v) }

    var vaultId: String?
        get() = prefs.getString(K_VAULT_ID, null)
        set(v) = prefs.edit { putString(K_VAULT_ID, v) }

    override var failures: Int
        get() = prefs.getInt(K_FAILURES, 0)
        set(v) = prefs.edit(commit = true) { putInt(K_FAILURES, v) }

    override var lastFailureAt: Long
        get() = prefs.getLong(K_LAST_FAILURE, 0)
        set(v) = prefs.edit(commit = true) { putLong(K_LAST_FAILURE, v) }

    /** When true, Present/Absent notification buttons write to the pending-actions queue without unlocking. */
    var quickActionsQueue: Boolean
        get() = prefs.getBoolean(K_QUICK_QUEUE, false)
        set(v) = prefs.edit { putBoolean(K_QUICK_QUEUE, v) }

    var theme: AppThemeMode
        get() = _themeMode.value
        set(value) {
            prefs.edit { putString(K_THEME_MODE, value.name) }
            _themeMode.value = value
        }

    val pinEnabled: Boolean
        get() = prefs.contains(K_PIN_HASH) && prefs.contains(K_PIN_SALT)

    var pinFailures: Int
        get() = prefs.getInt(K_PIN_FAILURES, 0)
        set(value) = prefs.edit(commit = true) { putInt(K_PIN_FAILURES, value) }

    var pinLastFailureAt: Long
        get() = prefs.getLong(K_PIN_LAST_FAILURE, 0)
        set(value) = prefs.edit(commit = true) { putLong(K_PIN_LAST_FAILURE, value) }

    fun pinCredential(): Pair<String, String>? {
        val salt = prefs.getString(K_PIN_SALT, null) ?: return null
        val hash = prefs.getString(K_PIN_HASH, null) ?: return null
        return salt to hash
    }

    fun setPinCredential(salt: String, hash: String) = prefs.edit(commit = true) {
        putString(K_PIN_SALT, salt)
        putString(K_PIN_HASH, hash)
    }

    fun clearPinCredential() = prefs.edit(commit = true) {
        remove(K_PIN_SALT)
        remove(K_PIN_HASH)
        putInt(K_PIN_FAILURES, 0)
        putLong(K_PIN_LAST_FAILURE, 0)
    }

    fun setPinLocked(locked: Boolean) {
        prefs.edit(commit = true) { putBoolean(K_PIN_LOCKED, locked) }
        _pinLocked.value = locked
    }

    fun biometricBlob(vaultId: String): Pair<String, String>? {
        if (prefs.getString(K_BIO_VAULT, null) != vaultId) return null
        val iv = prefs.getString(K_BIO_IV, null) ?: return null
        val ct = prefs.getString(K_BIO_CT, null) ?: return null
        return iv to ct
    }

    fun setBiometricBlob(vaultId: String, iv: String, ciphertext: String) = prefs.edit(commit = true) {
        putString(K_BIO_VAULT, vaultId)
        putString(K_BIO_IV, iv)
        putString(K_BIO_CT, ciphertext)
    }

    fun clearBiometric() = prefs.edit(commit = true) {
        remove(K_BIO_VAULT); remove(K_BIO_IV); remove(K_BIO_CT)
    }

    private companion object {
        const val K_TREE = "tree_uri"
        const val K_VAULT_ID = "vault_id"
        const val K_FAILURES = "failures"
        const val K_LAST_FAILURE = "last_failure"
        const val K_QUICK_QUEUE = "quick_actions_queue"
        const val K_THEME_MODE = "theme_mode"
        const val K_PIN_SALT = "pin_salt"
        const val K_PIN_HASH = "pin_hash"
        const val K_PIN_LOCKED = "pin_locked"
        const val K_PIN_FAILURES = "pin_failures"
        const val K_PIN_LAST_FAILURE = "pin_last_failure"
        const val K_BIO_VAULT = "bio_vault"
        const val K_BIO_IV = "bio_iv"
        const val K_BIO_CT = "bio_ct"
    }
}
