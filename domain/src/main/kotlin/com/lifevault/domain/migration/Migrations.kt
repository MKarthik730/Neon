package com.lifevault.domain.migration

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The file was written by a newer version of the app; refuse to read (and never overwrite) it. */
class NewerSchemaException(val found: Int, val supported: Int) :
    IllegalStateException("File schema v$found is newer than this app supports (v$supported). Update the app.")

/**
 * Upgrades a decoded JSON document step by step: `steps[n]` turns version n into n+1.
 * A missing `schemaVersion` is treated as version 0 (pre-release files), which step 0 upgrades.
 */
class SchemaMigrator(
    val currentVersion: Int,
    private val steps: Map<Int, (JsonObject) -> JsonObject> = emptyMap(),
) {
    init {
        for (v in 0 until currentVersion) {
            require(v in steps || v == 0) { "Missing migration step $v -> ${v + 1}" }
        }
    }

    fun versionOf(obj: JsonObject): Int = obj["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 0

    fun needsMigration(obj: JsonObject) = versionOf(obj) != currentVersion

    fun migrate(obj: JsonObject): JsonObject {
        var version = versionOf(obj)
        if (version > currentVersion) throw NewerSchemaException(version, currentVersion)
        var doc = obj
        while (version < currentVersion) {
            val step = steps[version] ?: identity
            doc = step(doc)
            version++
            doc = JsonObject(doc + ("schemaVersion" to JsonPrimitive(version)))
        }
        return doc
    }

    private companion object {
        val identity: (JsonObject) -> JsonObject = { it }
    }
}

/** Logical names of every vault data file. Physical file names are keyed hashes of these. */
object LogicalNames {
    const val SETTINGS = "settings"
    const val TIMETABLE = "timetable"
    const val HOLIDAYS = "holidays"
    const val RULES = "rules"
    const val EVENTS = "events"
    const val STUDY_INDEX = "study-index"
    const val MEDIA_INDEX = "media-index"

    const val ATTENDANCE_PREFIX = "attendance-"
    const val ENTRIES_PREFIX = "entries-"
    const val MOOD_PREFIX = "mood-"
    const val JOB_LOG_PREFIX = "job-log-"
    const val STUDY_PREFIX = "study-"

    val monthlyPrefixes = listOf(ATTENDANCE_PREFIX, ENTRIES_PREFIX, MOOD_PREFIX, JOB_LOG_PREFIX)

    fun monthly(prefix: String, ym: java.time.YearMonth) = prefix + ym.toString()
    fun studyPage(subjectId: String) = STUDY_PREFIX + subjectId
}

/** Current schema version and migration steps for each kind of file. */
object VaultSchemas {
    private val v1 = SchemaMigrator(currentVersion = 1)

    /**
     * Every file type is at v1 today. When a model changes, bump its CURRENT_SCHEMA and return a migrator
     * with a step for that file kind, e.g. for names starting with [LogicalNames.MOOD_PREFIX]:
     * `SchemaMigrator(2, mapOf(1 to { o -> JsonObject(o - "old" + ("new" to o.getValue("old"))) }))`.
     */
    @Suppress("UNUSED_PARAMETER")
    fun migratorFor(logicalName: String): SchemaMigrator = v1
}
