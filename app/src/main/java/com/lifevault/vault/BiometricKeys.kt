package com.lifevault.vault

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import com.lifevault.vault.crypto.VaultCrypto
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Optional biometric quick-unlock. A second copy of the master key bundle is wrapped with an AES-256-GCM
 * key in the Android Keystore that can only be used after a strong biometric authentication
 * (per-use CryptoObject) and is invalidated when new fingerprints/faces are enrolled.
 * The passphrase always remains the fallback; this copy never leaves the device.
 */
class BiometricKeys(private val context: Context, private val prefs: DevicePrefs) {

    fun isHardwareReady(): Boolean =
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun isEnabled(vaultId: String): Boolean = prefs.biometricBlob(vaultId) != null && keyStore().containsAlias(alias(vaultId))

    /** Cipher to authenticate for enrolment. */
    fun encryptCipher(vaultId: String): Cipher {
        val key = createKey(vaultId)
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    /** Cipher to authenticate for unlocking, or null when biometrics are not set up / were invalidated. */
    fun decryptCipher(vaultId: String): Cipher? {
        val (iv, _) = prefs.biometricBlob(vaultId) ?: return null
        val key = keyStore().getKey(alias(vaultId), null) as? SecretKey ?: return null
        return try {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.getDecoder().decode(iv))) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            disable(vaultId)
            null
        }
    }

    /** Stores [material] wrapped with an authenticated encrypt cipher. Zeroes [material]. */
    fun store(vaultId: String, authenticated: Cipher, material: ByteArray) {
        try {
            authenticated.updateAAD(VaultCrypto.biometricAad(vaultId))
            val ct = authenticated.doFinal(material)
            prefs.setBiometricBlob(vaultId, Base64.getEncoder().encodeToString(authenticated.iv), Base64.getEncoder().encodeToString(ct))
        } finally {
            material.fill(0)
        }
    }

    /** Unwraps the master key bundle with an authenticated decrypt cipher. */
    fun unwrap(vaultId: String, authenticated: Cipher): ByteArray {
        val (_, ct) = prefs.biometricBlob(vaultId) ?: throw IllegalStateException("Biometric unlock is not set up")
        authenticated.updateAAD(VaultCrypto.biometricAad(vaultId))
        return authenticated.doFinal(Base64.getDecoder().decode(ct))
    }

    fun disable(vaultId: String?) {
        prefs.clearBiometric()
        if (vaultId != null) runCatching { keyStore().deleteEntry(alias(vaultId)) }
    }

    private fun createKey(vaultId: String): SecretKey {
        val ks = keyStore()
        if (ks.containsAlias(alias(vaultId))) ks.deleteEntry(alias(vaultId))
        val builder = KeyGenParameterSpec.Builder(alias(vaultId), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(builder.build())
            generateKey()
        }
    }

    private fun keyStore() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private fun alias(vaultId: String) = "lifevault-bio-$vaultId"

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
