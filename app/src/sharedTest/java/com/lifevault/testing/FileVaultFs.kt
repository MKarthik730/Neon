package com.lifevault.testing

import com.lifevault.vault.storage.PathViolationException
import com.lifevault.vault.storage.VaultDir
import com.lifevault.vault.storage.VaultFs
import com.lifevault.vault.storage.VaultPath
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption

/**
 * java.io.File implementation of [VaultFs] with the same semantics as the SAF one
 * (rename refuses to overwrite). Every resolved file is checked to be inside [root] by canonical path.
 */
class FileVaultFs(val root: File) : VaultFs {
    private val canonicalRoot = root.canonicalFile

    /** Every mutating operation, for assertions. */
    val writes = mutableListOf<String>()

    override val displayName: String get() = root.name

    fun resolve(path: VaultPath): File {
        val f = if (path.dir.dirName == null) File(canonicalRoot, path.name) else File(File(canonicalRoot, path.dir.dirName!!), path.name)
        val c = f.canonicalFile
        if (!c.path.startsWith(canonicalRoot.path + File.separator)) throw PathViolationException("Outside vault: $c")
        return c
    }

    override fun ensureLayout() {
        for (d in VaultDir.subdirs) File(canonicalRoot, d.dirName!!).mkdirs()
    }

    override fun exists(path: VaultPath) = resolve(path).isFile

    override fun list(dir: VaultDir): Set<String> {
        val d = if (dir.dirName == null) canonicalRoot else File(canonicalRoot, dir.dirName!!)
        return d.listFiles()?.filter { it.isFile }?.map { it.name }?.toSet() ?: emptySet()
    }

    override fun size(path: VaultPath) = resolve(path).let { if (it.isFile) it.length() else -1 }

    override fun openRead(path: VaultPath): InputStream {
        val f = resolve(path)
        if (!f.isFile) throw FileNotFoundException(path.relative)
        return f.inputStream()
    }

    override fun openWrite(path: VaultPath): OutputStream {
        val f = resolve(path)
        f.parentFile.mkdirs()
        writes += "write ${path.relative}"
        return f.outputStream()
    }

    override fun openSeekable(path: VaultPath): SeekableByteChannel {
        val f = resolve(path)
        if (!f.isFile) throw FileNotFoundException(path.relative)
        return java.nio.file.Files.newByteChannel(f.toPath(), StandardOpenOption.READ)
    }

    override fun delete(path: VaultPath): Boolean {
        writes += "delete ${path.relative}"
        return resolve(path).delete()
    }

    override fun rename(from: VaultPath, newName: String): VaultPath {
        val target = from.sibling(newName)
        val src = resolve(from)
        val dst = resolve(target)
        if (dst.exists()) throw IOException("exists: ${target.relative}")
        if (!src.isFile) throw FileNotFoundException(from.relative)
        writes += "rename ${from.relative} -> ${target.relative}"
        if (!src.renameTo(dst)) throw IOException("rename failed")
        return target
    }

    override fun refresh() {}

    /** Test helper: raw write, bypassing the store (to simulate crashes/tampering). */
    fun rawWrite(path: VaultPath, bytes: ByteArray) {
        val f = resolve(path)
        f.parentFile.mkdirs()
        f.writeBytes(bytes)
    }

    fun rawFlipByte(path: VaultPath, index: Int) {
        RandomAccessFile(resolve(path), "rw").use { raf ->
            raf.seek(index.toLong())
            val b = raf.read()
            raf.seek(index.toLong())
            raf.write(b xor 0x01)
        }
    }
}

/** Wraps a [VaultFs] and throws on the N-th mutating call, to simulate a crash mid-write. */
class CrashingFs(private val inner: VaultFs, private val crashAt: Int) : VaultFs by inner {
    private var ops = 0
    class Crash : RuntimeException("simulated crash")

    private fun tick() { if (++ops == crashAt) throw Crash() }

    override fun openWrite(path: VaultPath): OutputStream {
        tick()
        val out = inner.openWrite(path)
        return object : OutputStream() {
            override fun write(b: Int) = out.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) {
                // Tear the write in half before crashing.
                if (ops + 1 == crashAt) { out.write(b, off, len / 2); out.close(); tick() }
                out.write(b, off, len)
            }
            override fun close() = out.close()
        }
    }
    override fun writeBytes(path: VaultPath, bytes: ByteArray) = openWrite(path).use { it.write(bytes) }
    override fun delete(path: VaultPath): Boolean { tick(); return inner.delete(path) }
    override fun rename(from: VaultPath, newName: String): VaultPath { tick(); return inner.rename(from, newName) }
}
