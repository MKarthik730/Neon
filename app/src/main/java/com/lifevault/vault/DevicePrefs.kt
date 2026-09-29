package com.lifevault.vault

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Small, non-sensitive, device-local settings kept in app-private storage (excluded from backup):
 * which folder is the vault, failed-attempt counters, the biometric-wrapped key (ciphertext under a
 * hardware Keystore key, useless off this device) and the notification quick-action preference.
 * No vault content ever lives here.
 */
class DevicePrefs(context: Context) : AttemptStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("device", Context.MODE_PRIVATE)

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
        const val K_BIO_VAULT = "bio_vault"
        const val K_BIO_IV = "bio_iv"
        const val K_BIO_CT = "bio_ct"
    }
}
