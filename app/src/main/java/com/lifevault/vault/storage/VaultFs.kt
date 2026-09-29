package com.lifevault.vault.storage

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.SeekableByteChannel

/** The vault folder is missing, was moved/deleted, or the app lost permission to it. */
class VaultFolderUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * All file access to the vault goes through this interface. Implementations must only ever touch
 * files under their root, addressed by validated [VaultPath]s.
 */
interface VaultFs {
    /** Human-readable folder name for the UI. */
    val displayName: String

    /** Creates the fixed sub-directories if missing. */
    fun ensureLayout()

    fun exists(path: VaultPath): Boolean

    /** File names in [dir]. */
    fun list(dir: VaultDir): Set<String>

    /** Size in bytes, or -1 if unknown. */
    fun size(path: VaultPath): Long

    fun openRead(path: VaultPath): InputStream

    /** Creates the file (or truncates it) and opens it for writing. */
    fun openWrite(path: VaultPath): OutputStream

    /** Read-only random access, used for streaming decryption with seeking. */
    fun openSeekable(path: VaultPath): SeekableByteChannel

    fun delete(path: VaultPath): Boolean

    /** Renames within the same directory. The target must not exist. */
    fun rename(from: VaultPath, newName: String): VaultPath

    /** Drops any cached directory listings (e.g. after the folder changed outside the app). */
    fun refresh()

    fun readBytes(path: VaultPath): ByteArray = openRead(path).use { it.readBytes() }

    fun writeBytes(path: VaultPath, bytes: ByteArray) = openWrite(path).use { it.write(bytes) }

    /** Deletes every file in [dir]. */
    fun wipe(dir: VaultDir) {
        for (name in list(dir)) {
            runCatching { delete(VaultPath.of(dir, name)) }
        }
    }
}
