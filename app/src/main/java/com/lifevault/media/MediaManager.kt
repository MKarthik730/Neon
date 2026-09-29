package com.lifevault.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.LruCache
import android.webkit.MimeTypeMap
import com.lifevault.domain.model.MediaItem
import com.lifevault.domain.model.MediaKind
import com.lifevault.domain.model.MediaSource
import com.lifevault.vault.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The media vault: import (copy + encrypt), in-app capture/recording, encrypted thumbnails, decrypting
 * images to memory, explicit "export decrypted copy", and secure delete. Bitmap caches are cleared on lock.
 */
class MediaManager(private val context: Context, private val session: VaultSession) {
    private val resolver = context.contentResolver
    private val blobs get() = session.blobs
    private val thumbCache = object : LruCache<String, Bitmap>(THUMB_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    init {
        session.onClose { thumbCache.evictAll() }
    }

    data class SourceInfo(val name: String, val mime: String, val size: Long, val canDelete: Boolean)

    fun describe(uri: Uri): SourceInfo {
        var name = "file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val mime = resolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val canDelete = runCatching {
            DocumentsContract.isDocumentUri(context, uri) &&
                resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c ->
                    c.moveToFirst() && (c.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0
                } == true
        }.getOrDefault(false)
        return SourceInfo(name, mime, size, canDelete)
    }

    /** Copies [uri] into the vault, encrypting on the fly, and indexes it. The original is not touched. */
    suspend fun import(uri: Uri): MediaItem = withContext(Dispatchers.IO) {
        val info = describe(uri)
        val kind = kindOf(info.mime)
        val id = blobs.newId()
        val size = (resolver.openInputStream(uri) ?: throw IOException("Cannot read ${info.name}")).use { blobs.writeFrom(id, it) }
        try {
            var width: Int? = null
            var height: Int? = null
            var duration: Long? = null
            var thumbId: String? = null
            when (kind) {
                MediaKind.IMAGE -> {
                    val bmp = decodeSampled({ resolver.openInputStream(uri)!! }, THUMB_PX)
                    if (bmp != null) {
                        val bounds = bounds { resolver.openInputStream(uri)!! }
                        val rotation = exifRotation { resolver.openInputStream(uri)!! }
                        val rotated = rotate(bmp, rotation)
                        width = if (rotation % 180 == 0) bounds.first else bounds.second
                        height = if (rotation % 180 == 0) bounds.second else bounds.first
                        thumbId = saveThumb(rotated)
                    }
                }
                MediaKind.AUDIO -> duration = runCatching {
                    MediaMetadataRetriever().run {
                        try {
                            setDataSource(context, uri)
                            extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        } finally {
                            release()
                        }
                    }
                }.getOrNull()
                MediaKind.OTHER -> {}
            }
            val item = MediaItem(
                id = id, kind = kind, originalName = info.name, mimeType = info.mime, sizeBytes = size,
                createdAt = System.currentTimeMillis(), source = MediaSource.IMPORT,
                durationMs = duration, width = width, height = height, thumbId = thumbId,
            )
            session.media.add(item)
            item
        } catch (e: Exception) {
            runCatching { blobs.delete(id, null) }
            throw e
        }
    }

    /** Deletes the user's original after import (only possible for SAF documents that allow it). */
    suspend fun deleteOriginal(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
    }

    /** Saves a JPEG captured in memory by CameraX. The bytes never touch disk unencrypted. */
    suspend fun saveCapturedJpeg(jpeg: ByteArray, rotationDegrees: Int): MediaItem = withContext(Dispatchers.Default) {
        var bytes = jpeg
        var full: Bitmap? = null
        if (rotationDegrees % 360 != 0) {
            full = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.let { rotate(it, rotationDegrees) }
            if (full != null) {
                bytes = ByteArrayOutputStream().use { bos -> full.compress(Bitmap.CompressFormat.JPEG, 92, bos); bos.toByteArray() }
            }
        }
        val b = bounds { ByteArrayInputStream(bytes) }
        val id = blobs.newId()
        withContext(Dispatchers.IO) { blobs.writeFrom(id, ByteArrayInputStream(bytes)) }
        val thumbSource = full?.let { scaleDown(it, THUMB_PX) } ?: decodeSampled({ ByteArrayInputStream(bytes) }, THUMB_PX)
        val thumbId = thumbSource?.let { withContext(Dispatchers.IO) { saveThumb(it) } }
        val size = bytes.size.toLong()
        bytes.fill(0)
        if (bytes !== jpeg) jpeg.fill(0)
        val item = MediaItem(
            id = id, kind = MediaKind.IMAGE, originalName = "Photo ${stamp()}.jpg", mimeType = "image/jpeg",
            sizeBytes = size, createdAt = System.currentTimeMillis(), source = MediaSource.CAMERA,
            width = b.first, height = b.second, thumbId = thumbId,
        )
        session.media.add(item)
        item
    }

    fun newRecorder() = AudioRecorder(blobs)

    suspend fun saveRecording(recorder: AudioRecorder, durationMs: Long): MediaItem {
        val item = MediaItem(
            id = recorder.blobId, kind = MediaKind.AUDIO, originalName = "Recording ${stamp()}.aac",
            mimeType = AudioRecorder.MIME, sizeBytes = withContext(Dispatchers.IO) { blobs.cipherSize(recorder.blobId) },
            createdAt = System.currentTimeMillis(), source = MediaSource.RECORDER, durationMs = durationMs,
        )
        session.media.add(item)
        return item
    }

    suspend fun thumbnail(item: MediaItem): Bitmap? {
        val tid = item.thumbId ?: return null
        thumbCache.get(tid)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = blobs.readThumb(tid) ?: return@runCatching null
                try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } finally { bytes.fill(0) }
            }.getOrNull()?.also { thumbCache.put(tid, it) }
        }
    }

    /** Decrypts an image into memory, downsampled to fit [maxPx]. */
    suspend fun loadImage(item: MediaItem, maxPx: Int = 2048): Bitmap? = withContext(Dispatchers.IO) {
        val open = { blobs.openDecryptingInput(item.id) }
        val bmp = decodeSampled(open, maxPx) ?: return@withContext null
        val rotation = if (item.source == MediaSource.IMPORT) exifRotation(open) else 0
        rotate(bmp, rotation)
    }

    /** The only path that writes plaintext: an explicit export to a location the user picked. */
    suspend fun exportDecrypted(item: MediaItem, target: Uri) = withContext(Dispatchers.IO) {
        blobs.openDecryptingInput(item.id).use { input ->
            (resolver.openOutputStream(target, "wt") ?: throw IOException("Cannot write to the chosen file")).use { input.copyTo(it) }
        }
    }

    suspend fun delete(item: MediaItem) = withContext(Dispatchers.IO) {
        blobs.delete(item.id, item.thumbId)
        item.thumbId?.let { thumbCache.remove(it) }
        session.media.remove(item.id)
    }

    suspend fun update(item: MediaItem) = session.media.save(item)

    private fun saveThumb(bmp: Bitmap): String {
        val id = blobs.newId()
        val bytes = ByteArrayOutputStream().use { bos -> bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos); bos.toByteArray() }
        try { blobs.writeThumb(id, bytes) } finally { bytes.fill(0) }
        thumbCache.put(id, bmp)
        return id
    }

    private fun bounds(open: () -> InputStream): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, o) }
        return o.outWidth to o.outHeight
    }

    private fun decodeSampled(open: () -> InputStream, maxPx: Int): Bitmap? {
        val (w, h) = bounds(open)
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / (sample * 2) >= maxPx || h / (sample * 2) >= maxPx) sample *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = open().use { BitmapFactory.decodeStream(it, null, o) } ?: return null
        return scaleDown(bmp, maxPx)
    }

    private fun scaleDown(bmp: Bitmap, maxPx: Int): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= maxPx) return bmp
        val f = maxPx.toFloat() / longest
        return Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt().coerceAtLeast(1), (bmp.height * f).toInt().coerceAtLeast(1), true)
    }

    private fun exifRotation(open: () -> InputStream): Int = runCatching {
        open().use { input ->
            when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }
    }.getOrDefault(0)

    private fun rotate(bmp: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bmp
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun stamp(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss"))

    companion object {
        const val THUMB_PX = 320
        private const val THUMB_CACHE_KB = 16 * 1024

        fun kindOf(mime: String) = when {
            mime.startsWith("image/") -> MediaKind.IMAGE
            mime.startsWith("audio/") -> MediaKind.AUDIO
            else -> MediaKind.OTHER
        }
    }
}
