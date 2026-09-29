package com.lifevault.vault.store

import com.lifevault.vault.crypto.VaultKeys
import com.lifevault.vault.storage.VaultDir
import com.lifevault.vault.storage.VaultFs
import com.lifevault.vault.storage.VaultPath
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.security.SecureRandom
import java.util.UUID

/**
 * Encrypted media blobs (`media/<uuid>`) and thumbnails (`thumbs/<uuid>`).
 * Media use Tink Streaming AEAD (AES256_GCM_HKDF_4KB): encryption streams, decryption streams or seeks,
 * and no plaintext copy is ever written. Associated data binds each blob to its id.
 */
class BlobStore(private val fs: VaultFs, private val keys: VaultKeys) {
    private val random = SecureRandom()

    fun newId(): String = UUID.randomUUID().toString()

    private fun media(id: String) = VaultPath.of(VaultDir.MEDIA, id)
    private fun thumb(id: String) = VaultPath.of(VaultDir.THUMBS, id)
    private fun mediaAad(id: String) = "lifevault/media/v1/$id".toByteArray()
    private fun thumbAad(id: String) = "lifevault/thumb/v1/$id".toByteArray()

    fun exists(id: String) = fs.exists(media(id))

    /** Plaintext written here is encrypted on the fly into `media/<id>`. Close it to finish the blob. */
    fun openEncryptingOutput(id: String): OutputStream {
        val raw = fs.openWrite(media(id))
        return try {
            keys.streaming.newEncryptingStream(raw, mediaAad(id))
        } catch (e: Exception) {
            raw.close()
            fs.delete(media(id))
            throw e
        }
    }

    /** Writes an entire blob from [source]; on failure the partial blob is removed. Returns plaintext bytes written. */
    fun writeFrom(id: String, source: InputStream): Long {
        var total = 0L
        try {
            CountingStream(openEncryptingOutput(id)).use { out ->
                source.copyTo(out, BUFFER)
                total = out.count
            }
        } catch (e: Exception) {
            runCatching { fs.delete(media(id)) }
            throw e
        }
        return total
    }

    fun openDecryptingInput(id: String): InputStream =
        keys.streaming.newDecryptingStream(fs.openRead(media(id)), mediaAad(id))

    /** Random-access plaintext view, used by the audio player to seek without decrypting from the start. */
    fun openSeekable(id: String): SeekableByteChannel {
        val channel = keys.streaming.newSeekableDecryptingChannel(fs.openSeekable(media(id)), mediaAad(id))
        try {
            // Tink's keyset decrypter picks the matching key on the first read; size() fails before that.
            channel.read(ByteBuffer.allocate(1))
            channel.position(0)
        } catch (e: Exception) {
            channel.close()
            throw e
        }
        return channel
    }

    fun cipherSize(id: String) = fs.size(media(id))

    fun writeThumb(id: String, jpeg: ByteArray) {
        fs.writeBytes(thumb(id), keys.aead.encrypt(jpeg, thumbAad(id)))
    }

    fun readThumb(id: String): ByteArray? =
        if (!fs.exists(thumb(id))) null else keys.aead.decrypt(fs.readBytes(thumb(id)), thumbAad(id))

    /**
     * Best-effort secure delete: overwrite with random bytes, then remove. Flash storage may keep old
     * blocks, which is why content is encrypted in the first place.
     */
    fun delete(id: String, thumbId: String?) {
        overwriteAndDelete(media(id))
        thumbId?.let { overwriteAndDelete(thumb(it)) }
    }

    fun listMedia(): Set<String> = fs.list(VaultDir.MEDIA)
    fun listThumbs(): Set<String> = fs.list(VaultDir.THUMBS)

    private fun overwriteAndDelete(p: VaultPath) {
        if (!fs.exists(p)) return
        val size = fs.size(p)
        if (size > 0) {
            runCatching {
                fs.openWrite(p).use { out ->
                    val buf = ByteArray(BUFFER)
                    var left = size
                    while (left > 0) {
                        random.nextBytes(buf)
                        val n = minOf(left, buf.size.toLong()).toInt()
                        out.write(buf, 0, n)
                        left -= n
                    }
                }
            }
        }
        if (!fs.delete(p)) throw IOException("Could not delete ${p.relative}")
    }

    private class CountingStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }

    private companion object { const val BUFFER = 64 * 1024 }
}
