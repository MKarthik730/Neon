package com.lifevault.vault.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.Mac
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.mac.MacConfig
import com.google.crypto.tink.mac.PredefinedMacParameters
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Thrown when a vault operation is attempted after the keys were destroyed (vault locked). */
class VaultLockedException : IllegalStateException("The vault is locked")

/**
 * The vault master key. It is a bundle of three random 256-bit Tink keysets generated at vault creation:
 *  - AEAD AES-256-GCM for data files and thumbnails,
 *  - Streaming AEAD AES256_GCM_HKDF_4KB for media (streamable and seekable),
 *  - HMAC-SHA256 to derive opaque on-disk file names from logical names.
 *
 * The serialised bundle ("material") is what gets wrapped by the passphrase, recovery and biometric keys.
 * [destroy] drops every reference so nothing can be decrypted until the vault is unlocked again.
 * (The JVM cannot guarantee memory is overwritten; we zero every byte array we own and drop the rest.)
 */
class VaultKeys private constructor(
    aeadHandle: KeysetHandle,
    streamingHandle: KeysetHandle,
    macHandle: KeysetHandle,
) {
    @Volatile private var handles: Triple<KeysetHandle, KeysetHandle, KeysetHandle>? =
        Triple(aeadHandle, streamingHandle, macHandle)
    @Volatile private var aeadP: Aead? = aeadHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    @Volatile private var streamingP: StreamingAead? =
        streamingHandle.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
    @Volatile private var macP: Mac? = macHandle.getPrimitive(RegistryConfiguration.get(), Mac::class.java)

    val aead: Aead get() = aeadP ?: throw VaultLockedException()
    val streaming: StreamingAead get() = streamingP ?: throw VaultLockedException()
    val isDestroyed: Boolean get() = handles == null

    /** Opaque, deterministic on-disk name for a logical file name such as `attendance-2026-09`. */
    fun nameFor(logicalName: String): String {
        val mac = macP ?: throw VaultLockedException()
        val tag = mac.computeMac((NAME_CONTEXT + logicalName).toByteArray(Charsets.UTF_8))
        // Tink prefixes the tag with a 5-byte key id; keep 160 bits of the tag itself.
        return tag.copyOfRange(tag.size - 32, tag.size - 12).toHex()
    }

    /** Serialised key bundle for wrapping. The caller must zero it after use. */
    fun exportMaterial(): ByteArray {
        val (a, s, m) = handles ?: throw VaultLockedException()
        val parts = listOf(a, s, m).map { TinkProtoKeysetFormat.serializeKeyset(it, InsecureSecretKeyAccess.get()) }
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeByte(BUNDLE_VERSION)
            for (p in parts) { out.writeInt(p.size); out.write(p) }
        }
        parts.forEach { it.fill(0) }
        return bos.toByteArray()
    }

    fun destroy() {
        handles = null
        aeadP = null
        streamingP = null
        macP = null
    }

    companion object {
        private const val BUNDLE_VERSION = 1
        private const val NAME_CONTEXT = "lifevault/name/v1/"
        private const val MAX_KEYSET_BYTES = 64 * 1024

        init {
            AeadConfig.register()
            StreamingAeadConfig.register()
            MacConfig.register()
        }

        fun generate(): VaultKeys = VaultKeys(
            KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM),
            KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_4KB),
            KeysetHandle.generateNew(PredefinedMacParameters.HMAC_SHA256_256BITTAG),
        )

        /** Rebuilds keys from unwrapped material. Zeroes [material]. */
        fun fromMaterial(material: ByteArray): VaultKeys {
            try {
                DataInputStream(material.inputStream()).use { input ->
                    val version = input.readUnsignedByte()
                    require(version == BUNDLE_VERSION) { "Unsupported key bundle version $version" }
                    val handles = List(3) {
                        val len = input.readInt()
                        require(len in 1..MAX_KEYSET_BYTES) { "Corrupt key bundle" }
                        val bytes = ByteArray(len).also { input.readFully(it) }
                        try { TinkProtoKeysetFormat.parseKeyset(bytes, InsecureSecretKeyAccess.get()) } finally { bytes.fill(0) }
                    }
                    return VaultKeys(handles[0], handles[1], handles[2])
                }
            } finally {
                material.fill(0)
            }
        }
    }
}
