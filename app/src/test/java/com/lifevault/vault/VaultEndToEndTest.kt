package com.lifevault.vault

import com.lifevault.domain.model.AttendanceStatus
import com.lifevault.domain.model.ChecklistItem
import com.lifevault.domain.model.Entry
import com.lifevault.domain.model.EntryType
import com.lifevault.domain.model.Event
import com.lifevault.domain.model.JobLogEntry
import com.lifevault.domain.model.MediaItem
import com.lifevault.domain.model.MediaKind
import com.lifevault.domain.model.MoodCheckIn
import com.lifevault.domain.model.Rule
import com.lifevault.domain.model.RuleSet
import com.lifevault.domain.model.Session
import com.lifevault.domain.model.Settings
import com.lifevault.domain.model.TimetableSlot
import com.lifevault.testing.FileVaultFs
import com.lifevault.vault.crypto.KdfCost
import com.lifevault.vault.crypto.VaultCrypto
import com.lifevault.vault.storage.VaultDir
import com.lifevault.vault.storage.VaultPath
import com.lifevault.vault.store.HeaderStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Proves the storage contract end to end on a real directory:
 *  - nothing is written outside the vault folder,
 *  - every file sits in the fixed layout with an opaque name,
 *  - no plaintext (subjects, notes, amounts, original file names, mood) appears in any file,
 *  - `.tmp/` is wiped and keys are destroyed on lock,
 *  - copying the folder elsewhere and unlocking restores everything (portable).
 */
class VaultEndToEndTest {
    @get:JUnitRule val tmp = TemporaryFolder()

    private val fast = KdfCost(1024, 1)
    private val secrets = listOf(
        "Quantum Physics", "SECRET-MOOD-NOTE", "holiday-photo.jpg", "4242.75", "Before the exam",
        "Phone in another room", "Chemistry test", "Lunch with Sam", "SECRET-STUDY-NOTE",
    )

    private suspend fun populate(session: VaultSession) {
        val today = LocalDate.of(2026, 9, 28)
        session.settings.set(Settings(vaultCreatedOn = today))
        session.timetable.addVersion("Sem", today.minusDays(30), listOf(TimetableSlot(DayOfWeek.MONDAY, Session.MORNING, LocalTime.of(9, 0), LocalTime.of(12, 0), listOf("Quantum Physics"))))
        session.attendance.mark(today, Session.MORNING, AttendanceStatus.PRESENT, listOf("Quantum Physics"))
        session.entries.upsert(Entry("e1", EntryType.MONEY, LocalDateTime.of(2026, 9, 28, 13, 0), 4242.75, "INR", "Food", "Lunch with Sam"))
        session.mood.upsert(MoodCheckIn("m1", today, level = 2, tags = listOf("sleep"), note = "SECRET-MOOD-NOTE"))
        session.rules.upsertSet(RuleSet("rs", "Before the exam", listOf(Rule("r1", "Phone in another room"))))
        session.jobLog.save(JobLogEntry("j1", "Before the exam", "rs", "Before the exam", LocalDateTime.of(2026, 9, 28, 15, 0)))
        session.events.upsert(Event("ev", title = "Chemistry test", date = today.plusDays(3)))
        val subj = session.study.addSubject("Quantum Physics")
        session.study.page(subj.id).set(com.lifevault.domain.model.StudyPage(subjectId = subj.id, notes = "SECRET-STUDY-NOTE", checklist = listOf(ChecklistItem("c", "Quantum Physics ch 1"))))
        val blob = session.blobs.newId()
        session.blobs.writeFrom(blob, ByteArray(50_000) { (it % 251).toByte() }.inputStream())
        val thumb = session.blobs.newId()
        session.blobs.writeThumb(thumb, ByteArray(300) { 7 })
        session.media.add(MediaItem(blob, MediaKind.IMAGE, "holiday-photo.jpg", "image/jpeg", 50_000, 0, thumbId = thumb, tags = listOf("Quantum Physics")))
        // Something scratch in .tmp that must disappear on lock.
        session.fs.writeBytes(VaultPath.of(VaultDir.TMP, "scratch1"), "Quantum Physics".toByteArray())
    }

    @Test
    fun `vault folder holds only opaque encrypted files and nothing leaks outside`() = runTest {
        val parent = tmp.newFolder("parent")
        val vaultDir = File(parent, "MyVault").apply { mkdirs() }
        val fs = FileVaultFs(vaultDir).also { it.ensureLayout() }
        val created = VaultCrypto.create("correct horse battery", fast)
        HeaderStore(fs).write(created.header)
        val session = VaultSession(fs, created.header, created.keys)
        populate(session)
        session.close()

        // 1. Nothing outside the vault folder.
        assertEquals(listOf("MyVault"), parent.list()!!.toList())
        assertTrue("every write went through the vault fs", fs.writes.isNotEmpty())

        // 2. Fixed layout, opaque names.
        val files = vaultDir.walkTopDown().filter { it.isFile }.toList()
        val hex = Regex("[0-9a-f]{40}(\\.bak)?")
        val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        for (f in files) {
            val rel = f.relativeTo(vaultDir).invariantSeparatorsPath
            when {
                rel == "vault.json" || rel == "vault.json.bak" -> {}
                rel.startsWith("data/") -> assertTrue(rel, hex.matches(f.name))
                rel.startsWith("media/") || rel.startsWith("thumbs/") -> assertTrue(rel, uuid.matches(f.name))
                else -> throw AssertionError("unexpected file $rel")
            }
        }

        // 3. No plaintext anywhere, including vault.json.
        for (f in files) {
            val text = String(f.readBytes(), Charsets.ISO_8859_1)
            for (s in secrets) assertFalse("'$s' found in ${f.relativeTo(vaultDir)}", text.contains(s))
        }

        // 4. Lock wiped .tmp and destroyed the keys.
        assertTrue(fs.list(VaultDir.TMP).isEmpty())
        assertTrue(session.keys.isDestroyed)
        assertTrue(session.isClosed)
    }

    @Test
    fun `a copied folder unlocks elsewhere with the passphrase`() = runTest {
        val original = tmp.newFolder("phoneA")
        val fsA = FileVaultFs(original).also { it.ensureLayout() }
        val created = VaultCrypto.create("correct horse battery", fast)
        HeaderStore(fsA).write(created.header)
        VaultSession(fsA, created.header, created.keys).also { populate(it) }.close()

        val copy = tmp.newFolder("phoneB")
        original.copyRecursively(copy, overwrite = true)
        val fsB = FileVaultFs(copy)
        val header = HeaderStore(fsB).read()!!
        val keys = VaultCrypto.unlockWithPassphrase(header, "correct horse battery")
        val s = VaultSession(fsB, header, keys)
        assertEquals("SECRET-MOOD-NOTE", s.mood.all().single().note)
        assertEquals("Lunch with Sam", s.entries.all().single().note)
        assertEquals(AttendanceStatus.PRESENT, s.attendance.on(LocalDate.of(2026, 9, 28))[Session.MORNING]!!.status)
        assertEquals("holiday-photo.jpg", s.media.get().items.single().originalName)
        val blob = s.media.get().items.single().id
        assertEquals(50_000, s.blobs.openDecryptingInput(blob).use { it.readBytes() }.size)
        s.close()
    }

    @Test
    fun `closing a session runs hooks and later access fails`() = runTest {
        val fs = FileVaultFs(tmp.newFolder("v")).also { it.ensureLayout() }
        val created = VaultCrypto.create("correct horse battery", fast)
        val s = VaultSession(fs, created.header, created.keys)
        var hookRan = false
        s.onClose { hookRan = true }
        s.close()
        assertTrue(hookRan)
        try {
            VaultSession(fs, created.header, created.keys).store // new session with destroyed keys
            s.store.read("settings", Settings.serializer())
            throw AssertionError("reading after lock must fail")
        } catch (e: com.lifevault.vault.crypto.VaultLockedException) {
        }
    }
}
