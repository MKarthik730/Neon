package com.lifevault.vault.store

import com.lifevault.Brand
import com.lifevault.domain.util.VaultJson
import com.lifevault.vault.crypto.VaultHeader
import com.lifevault.vault.storage.VaultFs
import com.lifevault.vault.storage.VaultPath
import kotlinx.serialization.json.Json

/** Thrown when vault.json is present but is not a vault this app understands. */
class InvalidVaultException(message: String) : Exception(message)

/** Reads and atomically replaces `vault.json` (plaintext by necessity: it holds only wrapped keys and KDF params). */
class HeaderStore(fs: VaultFs) {
    private val atomic = AtomicFiles(fs)
    private val path = VaultPath.header()
    private val json: Json = Json(from = VaultJson) { prettyPrint = true }

    fun read(): VaultHeader? = atomic.read(path) { bytes -> parse(bytes) }

    fun write(header: VaultHeader) {
        val bytes = json.encodeToString(VaultHeader.serializer(), header).toByteArray()
        atomic.write(path, bytes) { parse(it) == header }
    }

    private fun parse(bytes: ByteArray): VaultHeader {
        val h = VaultJson.decodeFromString(VaultHeader.serializer(), bytes.toString(Charsets.UTF_8))
        if (h.format != VaultHeader.FORMAT) throw InvalidVaultException("Not a ${Brand.NAME} folder")
        if (h.formatVersion > VaultHeader.FORMAT_VERSION) {
            throw InvalidVaultException("This vault was created by a newer app version (format ${h.formatVersion}). Update the app.")
        }
        return h
    }
}
