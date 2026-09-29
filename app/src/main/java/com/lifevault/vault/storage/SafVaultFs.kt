package com.lifevault.vault.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * [VaultFs] over a Storage Access Framework tree the user picked (ACTION_OPEN_DOCUMENT_TREE with a
 * persisted permission). Only DocumentsContract calls against that tree are used; every document id comes
 * from listing the vault's own directories, and every URI is checked to belong to the same tree.
 *
 * Directory listings are cached (name -> document id) so lookups do not re-query the provider.
 */
class SafVaultFs(context: Context, val treeUri: Uri) : VaultFs {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val rootDocId: String = DocumentsContract.getTreeDocumentId(treeUri)
    private val authority: String = treeUri.authority ?: throw PathViolationException("Tree URI has no authority")
    private val dirIds = ConcurrentHashMap<VaultDir, String>()
    private val listings = ConcurrentHashMap<VaultDir, ConcurrentHashMap<String, String>>()
    private val lock = Any()

    init { dirIds[VaultDir.ROOT] = rootDocId }

    override val displayName: String by lazy {
        query(docUri(rootDocId), Document.COLUMN_DISPLAY_NAME) ?: rootDocId.substringAfterLast(':').ifEmpty { "Vault folder" }
    }

    /** Throws if the vault folder is gone or permission was revoked. */
    fun checkAccessible() {
        try {
            resolver.query(docUri(rootDocId), arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use {
                if (!it.moveToFirst()) throw VaultFolderUnavailableException("The vault folder no longer exists.")
            } ?: throw VaultFolderUnavailableException("The vault folder cannot be opened.")
        } catch (e: SecurityException) {
            throw VaultFolderUnavailableException("Permission to the vault folder was lost. Pick the folder again.", e)
        } catch (e: IllegalArgumentException) {
            throw VaultFolderUnavailableException("The vault folder no longer exists.", e)
        }
    }

    override fun ensureLayout() {
        for (dir in VaultDir.subdirs) dirId(dir, create = true)
    }

    override fun exists(path: VaultPath) = docId(path) != null

    override fun list(dir: VaultDir): Set<String> {
        if (dir != VaultDir.ROOT && dirId(dir, create = false) == null) return emptySet()
        return listing(dir).keys.toSet()
    }

    override fun size(path: VaultPath): Long {
        val id = docId(path) ?: return -1
        return query(docUri(id), Document.COLUMN_SIZE)?.toLongOrNull() ?: -1
    }

    override fun openRead(path: VaultPath): InputStream {
        val uri = requireUri(path)
        return guarded { resolver.openInputStream(uri) } ?: throw FileNotFoundException(path.relative)
    }

    override fun openWrite(path: VaultPath): OutputStream {
        val uri = docId(path)?.let(::docUri) ?: create(path)
        return guarded { resolver.openOutputStream(uri, "wt") } ?: throw IOException("Cannot write ${path.relative}")
    }

    override fun openSeekable(path: VaultPath): SeekableByteChannel {
        val uri = requireUri(path)
        val pfd = guarded { resolver.openFileDescriptor(uri, "r") } ?: throw FileNotFoundException(path.relative)
        val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        return PfdChannel(stream, stream.channel)
    }

    override fun delete(path: VaultPath): Boolean {
        val id = docId(path) ?: return false
        val ok = guarded { DocumentsContract.deleteDocument(resolver, docUri(id)) }
        if (ok) listing(path.dir).remove(path.name)
        return ok
    }

    override fun rename(from: VaultPath, newName: String): VaultPath {
        val target = from.sibling(newName)
        synchronized(lock) {
            if (exists(target)) throw IOException("Cannot rename: ${target.relative} already exists")
            val id = docId(from) ?: throw FileNotFoundException(from.relative)
            val newUri = guarded { DocumentsContract.renameDocument(resolver, docUri(id), newName) }
            val newId = newUri?.let { requireInTree(it); DocumentsContract.getDocumentId(it) } ?: id
            val actual = query(docUri(newId), Document.COLUMN_DISPLAY_NAME)
            val map = listing(from.dir)
            map.remove(from.name)
            if (actual != null && actual != newName) {
                map[actual] = newId
                throw IOException("Storage provider renamed ${from.name} to '$actual' instead of '$newName'")
            }
            map[newName] = newId
            return target
        }
    }

    override fun refresh() {
        listings.clear()
        dirIds.keys.retainAll(setOf(VaultDir.ROOT))
    }

    // ---- internals ----

    private fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private fun requireUri(path: VaultPath): Uri = docUri(docId(path) ?: throw FileNotFoundException(path.relative))

    /** Rejects any document URI that does not belong to the vault tree. */
    private fun requireInTree(uri: Uri) {
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        if (!SafJail.isSameTree(authority, rootDocId, uri.authority, treeId)) {
            throw PathViolationException("URI is outside the vault folder")
        }
    }

    private fun docId(path: VaultPath): String? {
        if (path.dir != VaultDir.ROOT && dirId(path.dir, create = false) == null) return null
        return listing(path.dir)[path.name]
    }

    private fun dirId(dir: VaultDir, create: Boolean): String? {
        if (dir == VaultDir.ROOT) return rootDocId
        dirIds[dir]?.let { return it }
        synchronized(lock) {
            dirIds[dir]?.let { return it }
            val name = dir.dirName!!
            val existing = children(rootDocId).entries.firstOrNull { it.key == name && it.value.second }?.value?.first
            val id = existing ?: if (create) {
                val uri = guarded { DocumentsContract.createDocument(resolver, docUri(rootDocId), Document.MIME_TYPE_DIR, name) }
                    ?: throw IOException("Cannot create folder $name")
                requireInTree(uri)
                DocumentsContract.getDocumentId(uri)
            } else {
                return null
            }
            dirIds[dir] = id
            return id
        }
    }

    private fun listing(dir: VaultDir): ConcurrentHashMap<String, String> {
        listings[dir]?.let { return it }
        synchronized(lock) {
            listings[dir]?.let { return it }
            val parent = dirId(dir, create = false) ?: return ConcurrentHashMap()
            val map = ConcurrentHashMap<String, String>()
            for ((name, v) in children(parent)) if (!v.second) map[name] = v.first
            listings[dir] = map
            return map
        }
    }

    /** name -> (documentId, isDirectory) for the direct children of [parentDocId]. */
    private fun children(parentDocId: String): Map<String, Pair<String, Boolean>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val out = HashMap<String, Pair<String, Boolean>>()
        guarded {
            resolver.query(uri, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE), null, null, null)
        }?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                out[name] = id to (c.getString(2) == Document.MIME_TYPE_DIR)
            }
        } ?: throw VaultFolderUnavailableException("Cannot list the vault folder.")
        return out
    }

    private fun create(path: VaultPath): Uri = synchronized(lock) {
        docId(path)?.let { return docUri(it) }
        val parent = dirId(path.dir, create = true)!!
        val uri = guarded { DocumentsContract.createDocument(resolver, docUri(parent), MIME, path.name) }
            ?: throw IOException("Cannot create ${path.relative}")
        requireInTree(uri)
        val id = DocumentsContract.getDocumentId(uri)
        val actual = query(uri, Document.COLUMN_DISPLAY_NAME)
        if (actual != null && actual != path.name) {
            guarded { DocumentsContract.deleteDocument(resolver, uri) }
            throw IOException(
                "This storage location changes file names ('${path.name}' became '$actual'). " +
                    "Choose a folder on the device's own storage.",
            )
        }
        listing(path.dir)[path.name] = id
        uri
    }

    private fun query(uri: Uri, column: String): String? = guarded {
        resolver.query(uri, arrayOf(column), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        throw VaultFolderUnavailableException("Permission to the vault folder was lost. Pick the folder again.", e)
    } catch (e: IllegalArgumentException) {
        throw VaultFolderUnavailableException("A vault file or folder is missing (was the folder moved or deleted?).", e)
    }

    private class PfdChannel(private val stream: FileInputStream, private val ch: FileChannel) : SeekableByteChannel {
        override fun read(dst: ByteBuffer) = ch.read(dst)
        override fun write(src: ByteBuffer): Int = throw IOException("read-only")
        override fun position() = ch.position()
        override fun position(newPosition: Long): SeekableByteChannel { ch.position(newPosition); return this }
        override fun size() = ch.size()
        override fun truncate(size: Long): SeekableByteChannel = throw IOException("read-only")
        override fun isOpen() = ch.isOpen
        override fun close() { try { ch.close() } finally { stream.close() } }
    }

    private companion object { const val MIME = "application/octet-stream" }
}

/** Pure checks used by [SafVaultFs]; kept free of Android types so they can be unit tested. */
object SafJail {
    fun isSameTree(rootAuthority: String, rootTreeId: String, authority: String?, treeId: String?): Boolean =
        authority == rootAuthority && treeId == rootTreeId
}
