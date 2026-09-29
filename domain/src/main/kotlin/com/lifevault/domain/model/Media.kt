package com.lifevault.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class MediaKind { IMAGE, AUDIO, OTHER }

@Serializable
enum class MediaSource { IMPORT, CAMERA, RECORDER }

/**
 * Everything that describes a media blob. This encrypted index is the only place original names, types,
 * durations and tags exist; blobs on disk are random UUIDs with no extension.
 */
@Serializable
data class MediaItem(
    val id: String,
    val kind: MediaKind,
    val originalName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val source: MediaSource = MediaSource.IMPORT,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val thumbId: String? = null,
    val tags: List<String> = emptyList(),
    val note: String = "",
) {
    fun matches(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return originalName.lowercase().contains(q) ||
            note.lowercase().contains(q) ||
            tags.any { it.lowercase().contains(q) }
    }
}

@Serializable
data class MediaIndex(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val items: List<MediaItem> = emptyList(),
) {
    companion object { const val CURRENT_SCHEMA = 1 }
}
