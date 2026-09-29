package com.lifevault.vault.crypto

import kotlinx.serialization.Serializable
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import java.text.Normalizer

/** Argon2id parameters, stored in plaintext in vault.json next to each wrapped key. */
@Serializable
data class KdfParams(
    val algorithm: String = ALGORITHM,
    val version: Int = Argon2Parameters.ARGON2_VERSION_13,
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int = 1,
    /** Base64 salt, 16 random bytes. */
    val salt: String,
) {
    companion object { const val ALGORITHM = "argon2id" }
}

/** Cost settings without a salt; a fresh salt is generated each time a key is wrapped. */
data class KdfCost(val memoryKiB: Int, val iterations: Int, val parallelism: Int = 1)

object Argon2Kdf {
    const val KEY_BYTES = 32
    const val SALT_BYTES = 16
    const val DEFAULT_MEMORY_KIB = 32 * 1024
    const val MIN_ITERATIONS = 2
    const val MAX_ITERATIONS = 24
    const val TARGET_MILLIS = 400L

    private val random = SecureRandom()

    fun newParams(cost: KdfCost): KdfParams {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return KdfParams(memoryKiB = cost.memoryKiB, iterations = cost.iterations, parallelism = cost.parallelism, salt = B64.encode(salt))
    }

    /** Derives a 256-bit key-encryption key. The caller must zero the result when done. */
    fun derive(secret: ByteArray, params: KdfParams): ByteArray {
        require(params.algorithm == KdfParams.ALGORITHM) { "Unsupported KDF ${params.algorithm}" }
        require(params.memoryKiB in 8..(1024 * 1024) && params.iterations in 1..100 && params.parallelism in 1..16) {
            "KDF parameters out of range"
        }
        val argon = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(params.version)
            .withMemoryAsKB(params.memoryKiB)
            .withIterations(params.iterations)
            .withParallelism(params.parallelism)
            .withSalt(B64.decode(params.salt))
            .build()
        val out = ByteArray(KEY_BYTES)
        try {
            Argon2BytesGenerator().apply { init(argon) }.generateBytes(secret, out)
        } finally {
            argon.clear()
        }
        return out
    }

    /** Passphrases are NFKC-normalised so the same text typed on another keyboard/phone derives the same key. */
    fun passphraseBytes(passphrase: CharSequence): ByteArray =
        Normalizer.normalize(passphrase, Normalizer.Form.NFKC).toByteArray(Charsets.UTF_8)

    /**
     * Chooses an iteration count so one derivation takes roughly [targetMillis] on this device,
     * at a fixed memory cost. Runs a warm-up first so JIT compilation does not skew the measurement.
     */
    fun calibrate(
        targetMillis: Long = TARGET_MILLIS,
        memoryKiB: Int = DEFAULT_MEMORY_KIB,
        clock: () -> Long = System::nanoTime,
    ): KdfCost {
        val probe = newParams(KdfCost(memoryKiB, 1))
        val secret = ByteArray(16)
        derive(secret, probe).fill(0) // warm-up
        val t0 = clock()
        derive(secret, probe).fill(0)
        val perIterationMs = ((clock() - t0) / 1_000_000).coerceAtLeast(1)
        val iterations = (targetMillis / perIterationMs).toInt().coerceIn(MIN_ITERATIONS, MAX_ITERATIONS)
        return KdfCost(memoryKiB, iterations)
    }
}
