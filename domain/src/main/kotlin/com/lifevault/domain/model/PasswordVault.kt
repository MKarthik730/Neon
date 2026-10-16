package com.lifevault.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class PasswordVaultFile(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val entries: List<PasswordEntry> = emptyList(),
) {
    companion object { const val CURRENT_SCHEMA = 1 }
}

@Serializable
data class PasswordEntry(
    val id: String,
    val title: String,
    val fields: Map<String, String> = emptyMap(),
    val updatedAt: Long = 0,
)
