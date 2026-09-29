package com.lifevault.vault.crypto

import java.security.SecureRandom

/**
 * High-entropy recovery code: 160 random bits in Crockford Base32, shown as 8 groups of 4 characters,
 * e.g. `7K3M-Q9TD-...`. Input is forgiving: case, spaces and dashes are ignored and O/I/L are read as 0/1/1.
 */
object RecoveryCode {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val BYTES = 20
    const val LENGTH = 32

    private val random = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(BYTES).also(random::nextBytes)
        try {
            return format(encode(bytes))
        } finally {
            bytes.fill(0)
        }
    }

    /** Canonical form used as KDF input: 32 upper-case Crockford characters, or null if invalid. */
    fun normalize(input: String): String? {
        val sb = StringBuilder()
        for (ch in input.uppercase()) {
            when (ch) {
                ' ', '-', '\t', '\n' -> continue
                'O' -> sb.append('0')
                'I', 'L' -> sb.append('1')
                else -> if (ch in ALPHABET) sb.append(ch) else return null
            }
        }
        return if (sb.length == LENGTH) sb.toString() else null
    }

    fun format(canonical: String): String = canonical.chunked(4).joinToString("-")

    private fun encode(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }
}
