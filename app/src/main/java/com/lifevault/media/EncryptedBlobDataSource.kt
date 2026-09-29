package com.lifevault.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.lifevault.vault.store.BlobStore
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * Media3 DataSource that decrypts a vault blob on the fly from its Streaming AEAD ciphertext.
 * Seeking maps directly onto the seekable decrypting channel, so no plaintext file ever exists.
 * URIs look like `vaultblob:///<blob id>`.
 */
@UnstableApi
class EncryptedBlobDataSource(private val blobs: BlobStore) : BaseDataSource(/* isNetwork = */ false) {
    private var channel: SeekableByteChannel? = null
    private var uri: Uri? = null
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val id = idOf(dataSpec.uri) ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val ch = try {
            blobs.openSeekable(id)
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        channel = ch
        val size = ch.size()
        if (dataSpec.position > size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        ch.position(dataSpec.position)
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - dataSpec.position
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val ch = channel ?: throw IOException("Not opened")
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        val n = try {
            ch.read(ByteBuffer.wrap(buffer, offset, toRead))
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        if (n < 0) return C.RESULT_END_OF_INPUT
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            channel?.close()
        } finally {
            channel = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(private val blobs: BlobStore) : DataSource.Factory {
        override fun createDataSource(): DataSource = EncryptedBlobDataSource(blobs)
    }

    companion object {
        const val SCHEME = "vaultblob"
        fun uriFor(id: String): Uri = Uri.parse("$SCHEME:///$id")
        fun idOf(uri: Uri): String? = if (uri.scheme == SCHEME) uri.lastPathSegment else null
    }
}
