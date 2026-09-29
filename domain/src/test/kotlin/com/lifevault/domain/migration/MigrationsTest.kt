package com.lifevault.domain.migration

import com.lifevault.domain.model.AttendanceMonth
import com.lifevault.domain.model.AttendanceStatus
import com.lifevault.domain.model.Session
import com.lifevault.domain.model.Settings
import com.lifevault.domain.model.TimetableFile
import com.lifevault.domain.util.VaultJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class MigrationsTest {

    @Test
    fun `steps run in order and stamp the version`() {
        val migrator = SchemaMigrator(
            currentVersion = 3,
            steps = mapOf(
                1 to { o -> JsonObject(o - "name" + ("title" to o.getValue("name"))) },
                2 to { o -> JsonObject(o + ("tags" to kotlinx.serialization.json.JsonArray(emptyList()))) },
            ),
        )
        val v1 = VaultJson.parseToJsonElement("""{"schemaVersion":1,"name":"Maths"}""").jsonObject
        val out = migrator.migrate(v1)
        assertEquals(3, out["schemaVersion"]!!.jsonPrimitive.int)
        assertEquals(JsonPrimitive("Maths"), out["title"])
        assertFalse("name" in out)
        assertTrue("tags" in out)
    }

    @Test
    fun `missing schemaVersion is treated as v0`() {
        val m = SchemaMigrator(1)
        val obj = VaultJson.parseToJsonElement("""{"a":1}""").jsonObject
        assertEquals(0, m.versionOf(obj))
        assertTrue(m.needsMigration(obj))
        assertEquals(1, m.migrate(obj)["schemaVersion"]!!.jsonPrimitive.int)
    }

    @Test
    fun `current version is untouched`() {
        val m = SchemaMigrator(1)
        val obj = VaultJson.parseToJsonElement("""{"schemaVersion":1,"a":1}""").jsonObject
        assertFalse(m.needsMigration(obj))
        assertEquals(obj, m.migrate(obj))
    }

    @Test(expected = NewerSchemaException::class)
    fun `newer files are refused`() {
        SchemaMigrator(1).migrate(VaultJson.parseToJsonElement("""{"schemaVersion":7}""").jsonObject)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `gaps in the chain are rejected up front`() {
        SchemaMigrator(3, mapOf(1 to { it }))
    }

    @Test
    fun `models round trip through the vault json and ignore unknown keys`() {
        val month = AttendanceMonth(month = YearMonth.of(2026, 9))
            .upsert(com.lifevault.domain.model.AttendanceRecord(YearMonth.of(2026, 9).atDay(1), Session.MORNING, AttendanceStatus.PRESENT, listOf("Maths")))
        val text = VaultJson.encodeToString(AttendanceMonth.serializer(), month)
        assertTrue(text.contains("\"2026-09\""))
        assertEquals(month, VaultJson.decodeFromString(AttendanceMonth.serializer(), text))

        val withExtra = text.dropLast(1) + ",\"futureField\":true}"
        assertEquals(month, VaultJson.decodeFromString(AttendanceMonth.serializer(), withExtra))

        assertEquals(Settings(), VaultJson.decodeFromString(Settings.serializer(), "{}"))
        assertEquals(TimetableFile(), VaultJson.decodeFromString(TimetableFile.serializer(), "{}"))
    }
}
