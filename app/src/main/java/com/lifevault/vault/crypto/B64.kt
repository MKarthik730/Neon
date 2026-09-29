package com.lifevault.vault.crypto

import java.util.Base64

/** Standard Base64 without line breaks (java.util.Base64 is available from API 26). */
internal object B64 {
    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
}

internal fun ByteArray.toHex(): String {
    val hex = "0123456789abcdef"
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        sb.append(hex[v ushr 4]).append(hex[v and 0x0f])
    }
    return sb.toString()
}
