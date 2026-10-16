package com.lifevault.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** Device-only quick gate. It never unlocks a vault whose in-memory session has been closed. */
class PinLock(private val prefs: DevicePrefs) {
    private val guard = BruteForceGuard(object : AttemptStore {
        override var failures: Int
            get() = prefs.pinFailures
            set(value) { prefs.pinFailures = value }
        override var lastFailureAt: Long
            get() = prefs.pinLastFailureAt
            set(value) { prefs.pinLastFailureAt = value }
    })

    fun setPin(pin: String) {
        require(pin.matches(Regex("\\d{4}"))) { "PIN must be exactly four digits." }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val verifier = verifier(pin, salt, createKey())
        prefs.setPinCredential(Base64.getEncoder().encodeToString(salt), Base64.getEncoder().encodeToString(verifier))
        guard.recordSuccess()
        prefs.setPinLocked(false)
    }

    fun verifyPin(pin: String): Boolean {
        require(pin.matches(Regex("\\d{4}"))) { "PIN must be exactly four digits." }
        if (guard.remainingDelay() > 0) return false
        val credential = prefs.pinCredential()
        val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey
        if (credential == null || key == null) {
            guard.recordFailure()
            return false
        }
        val (saltString, hashString) = credential
        val salt = Base64.getDecoder().decode(saltString)
        val expected = Base64.getDecoder().decode(hashString)
        val actual = verifier(pin, salt, key)
        val valid = MessageDigest.isEqual(expected, actual)
        actual.fill(0)
        if (valid) guard.recordSuccess() else guard.recordFailure()
        return valid
    }

    fun changePin(current: String, next: String): Boolean {
        if (!verifyPin(current)) return false
        setPin(next)
        return true
    }

    fun disablePin(current: String): Boolean {
        if (!verifyPin(current)) return false
        clearPin()
        prefs.setPinLocked(false)
        return true
    }

    fun clearPin() {
        prefs.clearPinCredential()
        keyStore().deleteEntry(KEY_ALIAS)
    }

    fun retryDelay(): Long = guard.remainingDelay()

    private fun verifier(pin: String, salt: ByteArray, key: SecretKey): ByteArray =
        Mac.getInstance(HMAC).run {
            init(key)
            update(salt)
            doFinal(pin.toByteArray(Charsets.UTF_8))
        }

    private fun createKey(): SecretKey {
        val store = keyStore()
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun keyStore() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "lifevault-app-pin-verifier"
        const val HMAC = "HmacSHA256"
        const val SALT_BYTES = 16
    }
}
