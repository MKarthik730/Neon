package com.lifevault.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class StudySubject(
    val id: String,
    val name: String,
    /** Rule set to show when starting a study job for this subject. */
    val ruleSetId: String? = null,
    val archived: Boolean = false,
)

@Serializable
data class StudyIndex(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val subjects: List<StudySubject> = emptyList(),
) {
    companion object { const val CURRENT_SCHEMA = 1 }
}

@Serializable
data class ChecklistItem(val id: String, val text: String, val done: Boolean = false)

/** One page per subject: Markdown notes, a checklist and vault attachments. */
@Serializable
data class StudyPage(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val subjectId: String,
    val notes: String = "",
    val checklist: List<ChecklistItem> = emptyList(),
    val mediaIds: List<String> = emptyList(),
    val updatedAt: Long = 0,
) {
    companion object { const val CURRENT_SCHEMA = 1 }
}
