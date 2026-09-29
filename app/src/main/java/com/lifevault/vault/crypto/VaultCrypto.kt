package com.lifevault.vault.crypto

import com.google.crypto.tink.subtle.AesGcmJce
import kotlinx.serialization.Serializable
import java.security.GeneralSecurityException
import java.util.UUID

/** The passphrase or recovery code did not unwrap the master key. */
class WrongSecretException : GeneralSecurityException("Wrong passphrase or recovery code")

/** Master key wrapped under a key derived from a secret. */
@Serializable
data class WrappedKey(val kdf: KdfParams, val wrappedKey: String)

/**
 * `vault.json`: the only plaintext file in the vault, by necessity. It holds the format version,
 * KDF parameters + salts, and the master key wrapped by the passphrase and by the recovery code.
 */
@Serializable
data class VaultHeader(
    val format: String = FORMAT,
    val formatVersion: Int = FORMAT_VERSION,
    val vaultId: String,
    val createdAt: Long,
    val cipherSuite: String = CIPHER_SUITE,
    val passphrase: WrappedKey,
    val recovery: WrappedKey,
) {
    companion object {
        const val FORMAT = "lifevault-vault"
        const val FORMAT_VERSION = 1
        const val CIPHER_SUITE = "tink:AES256_GCM+AES256_GCM_HKDF_4KB+HMAC_SHA256;kek:argon2id+AES256_GCM"
    }
}

class CreatedVault(val header: VaultHeader, val keys: VaultKeys, val recoveryCode: String)

/**
 * Key hierarchy:
 *  passphrase --Argon2id--> KEK --AES-GCM--> master key bundle  (vault.json "passphrase")
 *  recovery   --Argon2id--> KEK --AES-GCM--> master key bundle  (vault.json "recovery")
 *  Keystore key (biometric) --AES-GCM--> master key bundle       (app-private, this device only)
 * Changing the passphrase only re-wraps the master key; no content is re-encrypted.
 */
object VaultCrypto {
    const val MIN_PASSPHRASE_LENGTH = 8

    private fun aad(vaultId: String, purpose: String) = "lifevault/v1/master/$vaultId/$purpose".toByteArray()

    fun create(passphrase: CharSequence, cost: KdfCost, now: Long = System.currentTimeMillis()): CreatedVault {
        val vaultId = UUID.randomUUID().toString()
        val keys = VaultKeys.generate()
        val recoveryCode = RecoveryCode.generate()
        val material = keys.exportMaterial()
        try {
            val header = VaultHeader(
                vaultId = vaultId,
                createdAt = now,
                passphrase = wrap(Argon2Kdf.passphraseBytes(passphrase), material, vaultId, PURPOSE_PASSPHRASE, cost),
                recovery = wrap(recoverySecret(recoveryCode), material, vaultId, PURPOSE_RECOVERY, cost),
            )
            return CreatedVault(header, keys, recoveryCode)
        } finally {
            material.fill(0)
        }
    }

    fun unlockWithPassphrase(header: VaultHeader, passphrase: CharSequence): VaultKeys =
        VaultKeys.fromMaterial(unwrap(Argon2Kdf.passphraseBytes(passphrase), header.passphrase, header.vaultId, PURPOSE_PASSPHRASE))

    fun unlockWithRecovery(header: VaultHeader, code: String): VaultKeys {
        val canonical = RecoveryCode.normalize(code) ?: throw WrongSecretException()
        return VaultKeys.fromMaterial(unwrap(canonical.toByteArray(), header.recovery, header.vaultId, PURPOSE_RECOVERY))
    }

    /** New passphrase (fresh salt); content keys are unchanged. */
    fun rewrapPassphrase(header: VaultHeader, keys: VaultKeys, newPassphrase: CharSequence, cost: KdfCost): VaultHeader {
        val material = keys.exportMaterial()
        try {
            return header.copy(passphrase = wrap(Argon2Kdf.passphraseBytes(newPassphrase), material, header.vaultId, PURPOSE_PASSPHRASE, cost))
        } finally {
            material.fill(0)
        }
    }

    /** Replaces the recovery code; the old one stops working. */
    fun rotateRecovery(header: VaultHeader, keys: VaultKeys, cost: KdfCost): Pair<VaultHeader, String> {
        val code = RecoveryCode.generate()
        val material = keys.exportMaterial()
        try {
            return header.copy(recovery = wrap(recoverySecret(code), material, header.vaultId, PURPOSE_RECOVERY, cost)) to code
        } finally {
            material.fill(0)
        }
    }

    fun biometricAad(vaultId: String) = aad(vaultId, PURPOSE_BIOMETRIC)

    private fun recoverySecret(code: String): ByteArray =
        (RecoveryCode.normalize(code) ?: error("generated code must be valid")).toByteArray()

    private fun wrap(secret: ByteArray, material: ByteArray, vaultId: String, purpose: String, cost: KdfCost): WrappedKey {
        val params = Argon2Kdf.newParams(cost)
        val kek = try { Argon2Kdf.derive(secret, params) } finally { secret.fill(0) }
        try {
            val wrapped = AesGcmJce(kek).encrypt(material, aad(vaultId, purpose))
            return WrappedKey(params, B64.encode(wrapped))
        } finally {
            kek.fill(0)
        }
    }

    private fun unwrap(secret: ByteArray, wrapped: WrappedKey, vaultId: String, purpose: String): ByteArray {
        val kek = try { Argon2Kdf.derive(secret, wrapped.kdf) } finally { secret.fill(0) }
        try {
            return AesGcmJce(kek).decrypt(B64.decode(wrapped.wrappedKey), aad(vaultId, purpose))
        } catch (e: GeneralSecurityException) {
            throw WrongSecretException()
        } finally {
            kek.fill(0)
        }
    }

    private const val PURPOSE_PASSPHRASE = "passphrase"
    private const val PURPOSE_RECOVERY = "recovery"
    private const val PURPOSE_BIOMETRIC = "biometric"
}
