package com.lifevault.backup

import com.lifevault.Brand
import android.content.Context
import android.net.Uri
import com.lifevault.domain.util.VaultJson
import com.lifevault.vault.VaultSession
import com.lifevault.vault.crypto.VaultCrypto
import com.lifevault.vault.crypto.VaultHeader
import com.lifevault.vault.crypto.WrongSecretException
import com.lifevault.vault.storage.PathViolationException
import com.lifevault.vault.storage.VaultDir
import com.lifevault.vault.storage.VaultFs
import com.lifevault.vault.storage.VaultPath
import com.lifevault.vault.store.AtomicFiles
import com.lifevault.vault.store.EncryptedStore
import com.lifevault.vault.store.HeaderStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class BackupManifest(
    val format: String = FORMAT,
    val version: Int = 1,
    val vaultId: String,
    val createdAt: Long,
    val files: Int,
) {
    companion object { const val FORMAT = "lifevault-backup" }
}

data class StagedBackup(val manifest: BackupManifest?, val header: VaultHeader, val files: Int)

sealed interface RestoreResult {
    data object Restored : RestoreResult
    data object WrongPassphrase : RestoreResult
    data class Invalid(val message: String) : RestoreResult
}

/**
 * "Export encrypted backup": one zip of the vault exactly as it is on disk (every entry is already
 * ciphertext, plus vault.json with wrapped keys). "Restore" stages the archive into `.tmp/`, verifies every
 * file decrypts with the backup's passphrase, and only then replaces the vault. An interrupted commit is
 * resumed on the next start from the staged copies.
 */
class BackupManager(private val context: Context, private val session: VaultSession) {
    private val fs get() = session.fs

    suspend fun export(target: Uri, progress: (Int, Int) -> Unit = { _, _ -> }): Int = withContext(Dispatchers.IO) {
        val entries = buildList {
            add(VaultPath.header())
            for (dir in listOf(VaultDir.DATA, VaultDir.MEDIA, VaultDir.THUMBS)) {
                for (name in fs.list(dir).sorted()) {
                    // Skip working copies; the primary file is what matters.
                    if (dir == VaultDir.DATA && AtomicFiles.isAuxiliary(name)) continue
                    add(VaultPath.of(dir, name))
                }
            }
        }
        (context.contentResolver.openOutputStream(target, "wt") ?: throw IOException("Cannot write the backup file")).use { raw ->
            ZipOutputStream(raw.buffered()).use { zip ->
                val manifest = BackupManifest(vaultId = session.header.vaultId, createdAt = System.currentTimeMillis(), files = entries.size)
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(VaultJson.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
                zip.closeEntry()
                entries.forEachIndexed { i, p ->
                    zip.putNextEntry(ZipEntry(p.relative))
                    fs.openRead(p).use { it.copyTo(zip, BUFFER) }
                    zip.closeEntry()
                    progress(i + 1, entries.size)
                }
            }
        }
        entries.size
    }

    /** Unpacks [source] into `.tmp/` (flat names) without touching the live vault. */
    suspend fun stage(source: Uri): StagedBackup = withContext(Dispatchers.IO) {
        fs.wipe(VaultDir.TMP)
        var manifest: BackupManifest? = null
        var header: VaultHeader? = null
        var count = 0
        (context.contentResolver.openInputStream(source) ?: throw IOException("Cannot read the backup file")).use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    if (e.name == MANIFEST) {
                        manifest = runCatching { VaultJson.decodeFromString(BackupManifest.serializer(), zip.readBytes().toString(Charsets.UTF_8)) }.getOrNull()
                        continue
                    }
                    // Zip-slip protection: every entry must be a valid vault path.
                    val p = try { VaultPath.parse(e.name) } catch (x: PathViolationException) { throw IOException("Unexpected file in backup: ${e.name}") }
                    val staged = stagedPath(p) ?: throw IOException("Unexpected file in backup: ${e.name}")
                    fs.openWrite(staged).use { out -> zip.copyTo(out, BUFFER) }
                    if (p == VaultPath.header()) {
                        header = HeaderStore(StagedHeaderFs(fs, staged)).read()
                    }
                    count++
                }
            }
        }
        val h = header ?: run { fs.wipe(VaultDir.TMP); throw IOException("This file is not a ${Brand.NAME} backup (no vault.json).") }
        StagedBackup(manifest, h, count)
    }

    /**
     * Verifies the staged backup with its passphrase, then replaces the vault. The current session must be
     * locked by the caller afterwards (the keys may have changed).
     */
    suspend fun verifyAndRestore(staged: StagedBackup, passphrase: String): RestoreResult = withContext(Dispatchers.Default) {
        val keys = try {
            VaultCrypto.unlockWithPassphrase(staged.header, passphrase)
        } catch (e: WrongSecretException) {
            return@withContext RestoreResult.WrongPassphrase
        }
        try {
            val bad = withContext(Dispatchers.IO) { verifyStaged(keys) }
            if (bad.isNotEmpty()) {
                return@withContext RestoreResult.Invalid("${bad.size} file(s) in the backup are damaged; nothing was changed.")
            }
        } finally {
            keys.destroy()
        }
        withContext(Dispatchers.IO) { commit(fs) }
        RestoreResult.Restored
    }

    fun discardStaged() = runCatching { fs.wipe(VaultDir.TMP) }

    private fun verifyStaged(keys: com.lifevault.vault.crypto.VaultKeys): List<String> {
        val bad = ArrayList<String>()
        for (name in fs.list(VaultDir.TMP)) {
            val p = VaultPath.of(VaultDir.TMP, name)
            val ok = runCatching {
                when {
                    name.startsWith(P_DATA) -> {
                        val physical = AtomicFiles.baseName(name.removePrefix(P_DATA))
                        keys.aead.decrypt(fs.readBytes(p), (EncryptedStore.AAD_PREFIX + physical).toByteArray()).fill(0)
                    }
                    name.startsWith(P_THUMB) -> {
                        keys.aead.decrypt(fs.readBytes(p), "lifevault/thumb/v1/${name.removePrefix(P_THUMB)}".toByteArray()).fill(0)
                    }
                    name.startsWith(P_MEDIA) -> verifyMedia(keys, p, name.removePrefix(P_MEDIA))
                    else -> Unit
                }
            }.isSuccess
            if (!ok) bad += name
        }
        return bad
    }

    /** Checks the header, first segment and last segment of a streaming blob (catches corruption and truncation). */
    private fun verifyMedia(keys: com.lifevault.vault.crypto.VaultKeys, p: VaultPath, id: String) {
        keys.streaming.newSeekableDecryptingChannel(fs.openSeekable(p), "lifevault/media/v1/$id".toByteArray()).use { ch ->
            val buf = ByteBuffer.allocate(64 * 1024)
            ch.read(buf)
            val size = ch.size()
            ch.position((size - 4096).coerceAtLeast(0))
            buf.clear()
            while (ch.read(buf) > 0) buf.clear()
        }
    }

    companion object {
        const val MANIFEST = "lifevault-backup.json"
        private const val BUFFER = 64 * 1024
        private const val P_HEADER = "h-"
        private const val P_DATA = "d-"
        private const val P_MEDIA = "m-"
        private const val P_THUMB = "t-"
        private const val MARKER = "restore-commit"
        private const val PHASE_DELETE = "delete"
        private const val PHASE_COPY = "copy"

        private fun stagedPath(p: VaultPath): VaultPath? = when (p.dir) {
            VaultDir.ROOT -> if (p.name == VaultPath.HEADER) VaultPath.of(VaultDir.TMP, P_HEADER + p.name) else null
            VaultDir.DATA -> VaultPath.of(VaultDir.TMP, P_DATA + p.name)
            VaultDir.MEDIA -> VaultPath.of(VaultDir.TMP, P_MEDIA + p.name)
            VaultDir.THUMBS -> VaultPath.of(VaultDir.TMP, P_THUMB + p.name)
            VaultDir.TMP -> null
        }

        private fun finalPath(stagedName: String): VaultPath? = when {
            stagedName.startsWith(P_HEADER) -> VaultPath.header()
            stagedName.startsWith(P_DATA) -> VaultPath.of(VaultDir.DATA, stagedName.removePrefix(P_DATA))
            stagedName.startsWith(P_MEDIA) -> VaultPath.of(VaultDir.MEDIA, stagedName.removePrefix(P_MEDIA))
            stagedName.startsWith(P_THUMB) -> VaultPath.of(VaultDir.THUMBS, stagedName.removePrefix(P_THUMB))
            else -> null
        }

        /** Replaces the vault with the staged files. Safe to re-run after a crash. */
        fun commit(fs: VaultFs) {
            val marker = VaultPath.of(VaultDir.TMP, MARKER)
            var phase = if (fs.exists(marker)) fs.readBytes(marker).toString(Charsets.UTF_8) else PHASE_DELETE
            if (phase == PHASE_DELETE) {
                fs.writeBytes(marker, PHASE_DELETE.toByteArray())
                for (dir in listOf(VaultDir.DATA, VaultDir.MEDIA, VaultDir.THUMBS)) fs.wipe(dir)
                phase = PHASE_COPY
                fs.writeBytes(marker, PHASE_COPY.toByteArray())
            }
            val staged = fs.list(VaultDir.TMP).filter { it != MARKER }
            var header: VaultPath? = null
            for (name in staged) {
                val src = VaultPath.of(VaultDir.TMP, name)
                val dst = finalPath(name) ?: continue
                if (dst == VaultPath.header()) { header = src; continue }
                copy(fs, src, dst)
                fs.delete(src)
            }
            header?.let { src ->
                val h = HeaderStore(StagedHeaderFs(fs, src)).read() ?: throw IOException("Staged vault.json is unreadable")
                HeaderStore(fs).write(h)
                fs.delete(src)
            }
            fs.delete(marker)
        }

        /** Called on start: finishes a restore that was interrupted mid-commit. */
        fun resumeInterruptedRestore(fs: VaultFs) {
            if (fs.exists(VaultPath.of(VaultDir.TMP, MARKER))) commit(fs)
        }

        private fun copy(fs: VaultFs, src: VaultPath, dst: VaultPath) {
            if (fs.exists(dst)) fs.delete(dst)
            fs.openRead(src).use { input: InputStream -> fs.openWrite(dst).use { out: OutputStream -> input.copyTo(out, BUFFER) } }
        }
    }

    /** Presents one staged file as `vault.json` so [HeaderStore] can parse it. */
    private class StagedHeaderFs(private val inner: VaultFs, private val staged: VaultPath) : VaultFs by inner {
        private fun map(p: VaultPath) = if (p == VaultPath.header()) staged else p
        override fun exists(path: VaultPath) = if (path.dir == VaultDir.ROOT) path == VaultPath.header() && inner.exists(staged) else inner.exists(path)
        override fun openRead(path: VaultPath) = inner.openRead(map(path))
        override fun readBytes(path: VaultPath) = inner.readBytes(map(path))
        override fun size(path: VaultPath) = inner.size(map(path))
        override fun openWrite(path: VaultPath) = throw IOException("read-only")
        override fun writeBytes(path: VaultPath, bytes: ByteArray) = throw IOException("read-only")
        override fun delete(path: VaultPath) = false
        override fun rename(from: VaultPath, newName: String) = throw IOException("read-only")
    }
}
