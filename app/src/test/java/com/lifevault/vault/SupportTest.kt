package com.lifevault.vault

import com.lifevault.backup.BackupManager
import com.lifevault.domain.model.AttendanceStatus
import com.lifevault.domain.model.MoodCheckIn
import com.lifevault.domain.model.Session
import com.lifevault.domain.notify.AlarmPlan
import com.lifevault.domain.notify.AlarmType
import com.lifevault.domain.notify.PlannedAlarm
import com.lifevault.notifications.AlarmPlanStore
import com.lifevault.notifications.Notifier
import com.lifevault.notifications.PendingActionsQueue
import com.lifevault.notifications.PendingAttendanceAction
import com.lifevault.reports.MoodExport
import com.lifevault.testing.FileVaultFs
import com.lifevault.vault.crypto.KdfCost
import com.lifevault.vault.crypto.VaultCrypto
import com.lifevault.vault.storage.VaultDir
import com.lifevault.vault.storage.VaultPath
import com.lifevault.vault.store.HeaderStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

class SupportTest {
    @get:Rule val tmp = TemporaryFolder()

    private class MemAttempts : AttemptStore {
        override var failures = 0
        override var lastFailureAt = 0L
    }

    @Test
    fun `brute force delay grows exponentially and resets on success`() {
        var now = 1_000_000L
        val store = MemAttempts()
        val g = BruteForceGuard(store) { now }
        repeat(3) { g.recordFailure() }
        assertEquals(0, g.remainingDelay())
        g.recordFailure()
        assertEquals(5_000, g.remainingDelay())
        now += 2_000
        assertEquals(3_000, g.remainingDelay())
        g.recordFailure()
        assertEquals(10_000, g.remainingDelay())
        g.recordFailure()
        assertEquals(20_000, g.remainingDelay())
        assertEquals(BruteForceGuard.MAX_DELAY_MS, BruteForceGuard.delayFor(50))
        now -= 60_000 // clock moved back: no shortcut
        assertEquals(20_000, g.remainingDelay())
        g.recordSuccess()
        assertEquals(0, g.remainingDelay())
        assertEquals(0, store.failures)
    }

    @Test
    fun `pending queue keeps only date, session and status, deduplicated`() {
        val q = PendingActionsQueue(File(tmp.root, "pending.json"))
        val d = LocalDate.of(2026, 9, 28)
        q.append(PendingAttendanceAction(d, Session.MORNING, AttendanceStatus.ABSENT, 1))
        q.append(PendingAttendanceAction(d, Session.MORNING, AttendanceStatus.PRESENT, 2))
        q.append(PendingAttendanceAction(d, Session.EVENING, AttendanceStatus.ABSENT, 3))
        val list = q.peek()
        assertEquals(2, list.size)
        assertEquals(AttendanceStatus.PRESENT, list.first { it.session == Session.MORNING }.status)
        q.remove(list.take(1))
        assertEquals(1, q.peek().size)
        q.remove(q.peek())
        assertFalse(File(tmp.root, "pending.json").exists())

        val fields = PendingAttendanceAction.serializer().descriptor.let { dsc -> (0 until dsc.elementsCount).map { dsc.getElementName(it) } }
        assertEquals(listOf("date", "session", "status", "at"), fields)
    }

    @Test
    fun `queued notification marks are merged into the vault and then removed`() = kotlinx.coroutines.test.runTest {
        val fs = FileVaultFs(tmp.newFolder("vault")).also { it.ensureLayout() }
        val created = VaultCrypto.create("passphrase here", KdfCost(1024, 1))
        val session = VaultSession(fs, created.header, created.keys)
        val day = LocalDate.of(2026, 9, 28) // Monday
        session.timetable.addVersion(
            "Sem", day,
            listOf(com.lifevault.domain.model.TimetableSlot(day.dayOfWeek, Session.EVENING, java.time.LocalTime.of(14, 0), java.time.LocalTime.of(17, 0), listOf("Physics"))),
        )
        val queue = PendingActionsQueue(File(tmp.root, "pending.json"))
        queue.append(PendingAttendanceAction(day, Session.EVENING, AttendanceStatus.PRESENT, 1))

        assertEquals(1, com.lifevault.notifications.PendingMerge.merge(session, queue))
        val rec = session.attendance.on(day)[Session.EVENING]!!
        assertEquals(AttendanceStatus.PRESENT, rec.status)
        assertEquals(listOf("Physics"), rec.subjects) // subject filled in from the timetable, never stored in the queue
        assertTrue(queue.peek().isEmpty())
        assertEquals(0, com.lifevault.notifications.PendingMerge.merge(session, queue))
        session.close()
    }

    @Test
    fun `an interrupted merge keeps the queue for the next unlock`() = kotlinx.coroutines.test.runTest {
        val fs = FileVaultFs(tmp.newFolder("vault2")).also { it.ensureLayout() }
        val created = VaultCrypto.create("passphrase here", KdfCost(1024, 1))
        val session = VaultSession(fs, created.header, created.keys)
        val queue = PendingActionsQueue(File(tmp.root, "pending2.json"))
        queue.append(PendingAttendanceAction(LocalDate.of(2026, 9, 28), Session.MORNING, AttendanceStatus.ABSENT, 1))
        session.close() // vault locked before the merge could run
        try {
            com.lifevault.notifications.PendingMerge.merge(session, queue)
            throw AssertionError("merge on a locked vault must fail")
        } catch (e: com.lifevault.vault.crypto.VaultLockedException) {
        }
        assertEquals(1, queue.peek().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `pending queue refuses anything but present or absent`() {
        PendingActionsQueue(File(tmp.root, "q.json")).append(PendingAttendanceAction(LocalDate.now(), Session.MORNING, AttendanceStatus.CANCELLED, 0))
    }

    @Test
    fun `alarm plan store hands out due alarms once and drops stale ones`() {
        val store = AlarmPlanStore(File(tmp.root, "plan.json"))
        val now = LocalDateTime.of(2026, 9, 28, 12, 0)
        store.save(
            AlarmPlan(
                alarms = listOf(
                    PlannedAlarm("old", AlarmType.MOOD, now.minusHours(10)),
                    PlannedAlarm("due", AlarmType.ATTENDANCE, now.minusMinutes(1), LocalDate.of(2026, 9, 28), Session.MORNING),
                    PlannedAlarm("soon", AlarmType.EVENT, now.plusSeconds(10), refId = "e"),
                    PlannedAlarm("later", AlarmType.SUMMARY, now.plusHours(3)),
                ),
            ),
        )
        assertEquals(listOf("due", "soon"), store.takeDue(now).map { it.key })
        assertTrue(store.takeDue(now).isEmpty())
        assertEquals("later", store.next(now)!!.key)
    }

    @Test
    fun `notification text never carries vault data`() {
        val alarms = listOf(
            PlannedAlarm("a", AlarmType.ATTENDANCE, LocalDateTime.now(), LocalDate.now(), Session.EVENING),
            PlannedAlarm("b", AlarmType.EVENT, LocalDateTime.now(), refId = "secret-event-id"),
            PlannedAlarm("c", AlarmType.MOOD, LocalDateTime.now()),
            PlannedAlarm("d", AlarmType.RULES, LocalDateTime.now(), refId = "rule-set-id"),
            PlannedAlarm("e", AlarmType.SUMMARY, LocalDateTime.now()),
        )
        for (a in alarms) {
            val c = Notifier.contentFor(a)
            val text = c.title + " " + c.text
            assertFalse(text.contains("secret-event-id"))
            assertFalse(text.contains("rule-set-id"))
            if (a.type == AlarmType.MOOD) {
                // Generic prompt only: no level, emoji or label.
                assertFalse(Regex("[1-5]").containsMatchIn(text))
                for (l in com.lifevault.domain.model.MoodScale.label.values) assertFalse(text.contains(l, ignoreCase = true))
            }
        }
        assertEquals("Mark Evening attendance", Notifier.contentFor(alarms[0]).title)
    }

    @Test
    fun `mood csv export escapes and can omit notes`() {
        val list = listOf(MoodCheckIn("1", LocalDate.of(2026, 9, 1), level = 4, tags = listOf("study", "sleep"), note = "said \"hi\", ok"))
        val with = MoodExport.csv(list, includeNotes = true)
        assertTrue(with.contains("\"said \"\"hi\"\", ok\""))
        val without = MoodExport.csv(list, includeNotes = false)
        assertFalse(without.contains("hi"))
        assertTrue(without.lines()[1].startsWith("2026-09-01,Today,4,Good,study|sleep"))
    }

    @Test
    fun `restore commit replaces the vault and resumes after a crash`() {
        val fs = FileVaultFs(tmp.newFolder("vault")).also { it.ensureLayout() }
        val old = VaultCrypto.create("old passphrase", KdfCost(1024, 1))
        HeaderStore(fs).write(old.header)
        fs.writeBytes(VaultPath.of(VaultDir.DATA, "aaaa"), byteArrayOf(1))
        fs.writeBytes(VaultPath.of(VaultDir.MEDIA, "old-media"), byteArrayOf(2))

        val backup = VaultCrypto.create("backup passphrase", KdfCost(1024, 1))
        val headerBytes = FileVaultFs(tmp.newFolder("b")).let { b -> HeaderStore(b).write(backup.header); b.readBytes(VaultPath.header()) }
        fs.writeBytes(VaultPath.of(VaultDir.TMP, "h-vault.json"), headerBytes)
        fs.writeBytes(VaultPath.of(VaultDir.TMP, "d-bbbb"), byteArrayOf(3))
        fs.writeBytes(VaultPath.of(VaultDir.TMP, "m-new-media"), byteArrayOf(4))
        // Simulate a crash after the delete phase finished.
        fs.writeBytes(VaultPath.of(VaultDir.TMP, "restore-commit"), "copy".toByteArray())
        fs.delete(VaultPath.of(VaultDir.DATA, "aaaa"))
        fs.delete(VaultPath.of(VaultDir.MEDIA, "old-media"))

        BackupManager.resumeInterruptedRestore(fs)

        assertEquals(setOf("bbbb"), fs.list(VaultDir.DATA))
        assertEquals(setOf("new-media"), fs.list(VaultDir.MEDIA))
        assertTrue(fs.list(VaultDir.TMP).isEmpty())
        assertEquals(backup.header.vaultId, HeaderStore(fs).read()!!.vaultId)
        VaultCrypto.unlockWithPassphrase(HeaderStore(fs).read()!!, "backup passphrase")
    }
}
