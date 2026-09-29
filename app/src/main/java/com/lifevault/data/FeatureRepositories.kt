package com.lifevault.data

import com.lifevault.domain.migration.LogicalNames
import com.lifevault.domain.model.AttendanceMonth
import com.lifevault.domain.model.AttendanceRecord
import com.lifevault.domain.model.AttendanceStatus
import com.lifevault.domain.model.EntriesMonth
import com.lifevault.domain.model.Entry
import com.lifevault.domain.model.EntryType
import com.lifevault.domain.model.Event
import com.lifevault.domain.model.EventsFile
import com.lifevault.domain.model.Holiday
import com.lifevault.domain.model.HolidaysFile
import com.lifevault.domain.model.JobLogEntry
import com.lifevault.domain.model.JobLogMonth
import com.lifevault.domain.model.MediaIndex
import com.lifevault.domain.model.MediaItem
import com.lifevault.domain.model.MoodCheckIn
import com.lifevault.domain.model.MoodMonth
import com.lifevault.domain.model.RuleReminder
import com.lifevault.domain.model.RuleSet
import com.lifevault.domain.model.RulesFile
import com.lifevault.domain.model.Session
import com.lifevault.domain.model.Settings
import com.lifevault.domain.model.StudyIndex
import com.lifevault.domain.model.StudyPage
import com.lifevault.domain.model.StudySubject
import com.lifevault.domain.model.TimetableFile
import com.lifevault.domain.model.TimetableSlot
import com.lifevault.domain.model.TimetableVersion
import com.lifevault.domain.util.Ids
import com.lifevault.vault.store.EncryptedStore
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.ConcurrentHashMap

class SettingsRepository(store: EncryptedStore) :
    DocRepository<Settings>(store, LogicalNames.SETTINGS, Settings.serializer(), { Settings(vaultCreatedOn = LocalDate.now()) })

class TimetableRepository(store: EncryptedStore) :
    DocRepository<TimetableFile>(store, LogicalNames.TIMETABLE, TimetableFile.serializer(), { TimetableFile() }) {

    /**
     * Adds a timetable starting on [from]. Any open-ended version that started earlier is closed the day before,
     * so past attendance keeps being evaluated against the timetable that was in force then.
     */
    suspend fun addVersion(name: String, from: LocalDate, slots: List<TimetableSlot>, to: LocalDate? = null): TimetableVersion {
        val v = TimetableVersion(Ids.new(), name.ifBlank { "Timetable" }, from, to, slots)
        update { file ->
            val closed = file.versions.map { old ->
                if (old.effectiveFrom.isBefore(from) && (old.effectiveTo == null || !old.effectiveTo!!.isBefore(from))) {
                    old.copy(effectiveTo = from.minusDays(1))
                } else {
                    old
                }
            }
            file.copy(versions = closed + v)
        }
        return v
    }

    suspend fun saveVersion(version: TimetableVersion) = update { f ->
        f.copy(versions = f.versions.map { if (it.id == version.id) version else it })
    }

    suspend fun deleteVersion(id: String) = update { f -> f.copy(versions = f.versions.filterNot { it.id == id }) }
}

class HolidayRepository(store: EncryptedStore) :
    DocRepository<HolidaysFile>(store, LogicalNames.HOLIDAYS, HolidaysFile.serializer(), { HolidaysFile() }) {

    suspend fun upsert(h: Holiday) = update { f -> f.copy(holidays = (f.holidays.filterNot { it.id == h.id } + h).sortedBy { it.start }) }
    suspend fun addAll(list: List<Holiday>) = update { f -> f.copy(holidays = (f.holidays + list).sortedBy { it.start }) }
    suspend fun delete(id: String) = update { f -> f.copy(holidays = f.holidays.filterNot { it.id == id }) }
    suspend fun setOffDays(days: Set<DayOfWeek>) = update { f -> f.copy(weeklyOffDays = days.sorted()) }
}

class AttendanceRepository(store: EncryptedStore) :
    MonthlyRepository<AttendanceMonth>(store, LogicalNames.ATTENDANCE_PREFIX, AttendanceMonth.serializer(), { AttendanceMonth(month = it) }) {

    /** Sets the status of one session; `null` clears it back to Pending. */
    suspend fun mark(date: LocalDate, session: Session, status: AttendanceStatus?, subjects: List<String>) {
        update(YearMonth.from(date)) { m ->
            if (status == null) m.remove(date, session)
            else m.upsert(AttendanceRecord(date, session, status, subjects, System.currentTimeMillis()))
        }
    }

    suspend fun between(from: LocalDate, to: LocalDate): List<AttendanceRecord> =
        months(YearMonth.from(from), YearMonth.from(to)).flatMap { it.records }
            .filter { !it.date.isBefore(from) && !it.date.isAfter(to) }

    suspend fun on(date: LocalDate): Map<Session, AttendanceRecord> =
        month(YearMonth.from(date)).records.filter { it.date == date }.associateBy { it.session }
}

class EntryRepository(store: EncryptedStore) :
    MonthlyRepository<EntriesMonth>(store, LogicalNames.ENTRIES_PREFIX, EntriesMonth.serializer(), { EntriesMonth(month = it) }) {

    /** Saves [entry]; if its date moved to another month, removes it from the old month file. */
    suspend fun upsert(entry: Entry, previous: Entry? = null) {
        val ym = YearMonth.from(entry.at)
        if (previous != null && YearMonth.from(previous.at) != ym) {
            update(YearMonth.from(previous.at)) { m -> m.copy(entries = m.entries.filterNot { it.id == previous.id }) }
        }
        update(ym) { m -> m.copy(entries = (m.entries.filterNot { it.id == entry.id } + entry).sortedByDescending { it.at }) }
    }

    suspend fun delete(entry: Entry) {
        update(YearMonth.from(entry.at)) { m -> m.copy(entries = m.entries.filterNot { it.id == entry.id }) }
    }

    suspend fun between(from: LocalDate, to: LocalDate, type: EntryType? = null): List<Entry> =
        months(YearMonth.from(from), YearMonth.from(to)).flatMap { it.entries }
            .filter { val d = it.at.toLocalDate(); !d.isBefore(from) && !d.isAfter(to) }
            .filter { type == null || it.type == type }
            .sortedByDescending { it.at }

    suspend fun all(): List<Entry> = existingMonths().flatMap { month(it).entries }
}

class MoodRepository(store: EncryptedStore) :
    MonthlyRepository<MoodMonth>(store, LogicalNames.MOOD_PREFIX, MoodMonth.serializer(), { MoodMonth(month = it) }) {

    suspend fun upsert(c: MoodCheckIn, previous: MoodCheckIn? = null) {
        if (previous != null && YearMonth.from(previous.date) != YearMonth.from(c.date)) delete(previous)
        update(YearMonth.from(c.date)) { m ->
            // One check-in per (date, slot).
            m.copy(entries = (m.entries.filterNot { it.id == c.id || (it.date == c.date && it.slot == c.slot) } + c).sortedBy { it.date })
        }
    }

    suspend fun delete(c: MoodCheckIn) {
        update(YearMonth.from(c.date)) { m -> m.copy(entries = m.entries.filterNot { it.id == c.id }) }
    }

    suspend fun between(from: LocalDate, to: LocalDate): List<MoodCheckIn> =
        months(YearMonth.from(from), YearMonth.from(to)).flatMap { it.entries }
            .filter { !it.date.isBefore(from) && !it.date.isAfter(to) }

    suspend fun all(): List<MoodCheckIn> = existingMonths().flatMap { month(it).entries }
}

class RulesRepository(store: EncryptedStore) :
    DocRepository<RulesFile>(store, LogicalNames.RULES, RulesFile.serializer(), { RulesFile() }) {

    suspend fun upsertSet(set: RuleSet) = update { f ->
        val exists = f.sets.any { it.id == set.id }
        f.copy(sets = if (exists) f.sets.map { if (it.id == set.id) set else it } else f.sets + set)
    }

    suspend fun deleteSet(id: String) = update { f ->
        f.copy(sets = f.sets.filterNot { it.id == id }, reminders = f.reminders.filterNot { it.ruleSetId == id })
    }

    suspend fun upsertReminder(r: RuleReminder) = update { f ->
        f.copy(reminders = f.reminders.filterNot { it.id == r.id } + r)
    }

    suspend fun deleteReminder(id: String) = update { f -> f.copy(reminders = f.reminders.filterNot { it.id == id }) }
}

class JobLogRepository(store: EncryptedStore) :
    MonthlyRepository<JobLogMonth>(store, LogicalNames.JOB_LOG_PREFIX, JobLogMonth.serializer(), { JobLogMonth(month = it) }) {

    suspend fun save(entry: JobLogEntry) {
        update(YearMonth.from(entry.startedAt)) { m -> m.copy(entries = m.entries.filterNot { it.id == entry.id } + entry) }
    }

    suspend fun delete(entry: JobLogEntry) {
        update(YearMonth.from(entry.startedAt)) { m -> m.copy(entries = m.entries.filterNot { it.id == entry.id }) }
    }

    suspend fun all(): List<JobLogEntry> = existingMonths().flatMap { month(it).entries }.sortedByDescending { it.startedAt }
}

class EventRepository(store: EncryptedStore) :
    DocRepository<EventsFile>(store, LogicalNames.EVENTS, EventsFile.serializer(), { EventsFile() }) {

    suspend fun upsert(e: Event) = update { f -> f.copy(events = (f.events.filterNot { it.id == e.id } + e).sortedBy { it.startsAt() }) }
    suspend fun delete(id: String) = update { f -> f.copy(events = f.events.filterNot { it.id == id }) }
}

class StudyRepository(private val store: EncryptedStore) {
    val index = DocRepository(store, LogicalNames.STUDY_INDEX, StudyIndex.serializer()) { StudyIndex() }
    private val pages = ConcurrentHashMap<String, DocRepository<StudyPage>>()

    fun page(subjectId: String): DocRepository<StudyPage> = pages.getOrPut(subjectId) {
        DocRepository(store, LogicalNames.studyPage(subjectId), StudyPage.serializer()) { StudyPage(subjectId = subjectId) }
    }

    suspend fun addSubject(name: String): StudySubject {
        val existing = index.get().subjects.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        if (existing != null) return existing
        val s = StudySubject(Ids.new(), name.trim())
        index.update { it.copy(subjects = it.subjects + s) }
        return s
    }

    suspend fun saveSubject(s: StudySubject) = index.update { idx -> idx.copy(subjects = idx.subjects.map { if (it.id == s.id) s else it }) }

    suspend fun deleteSubject(id: String) {
        index.update { idx -> idx.copy(subjects = idx.subjects.filterNot { it.id == id }) }
        store.delete(LogicalNames.studyPage(id))
        pages.remove(id)
    }
}

class MediaIndexRepository(store: EncryptedStore) :
    DocRepository<MediaIndex>(store, LogicalNames.MEDIA_INDEX, MediaIndex.serializer(), { MediaIndex() }) {

    suspend fun add(item: MediaItem) = update { it.copy(items = listOf(item) + it.items.filterNot { i -> i.id == item.id }) }
    suspend fun save(item: MediaItem) = update { idx -> idx.copy(items = idx.items.map { if (it.id == item.id) item else it }) }
    suspend fun remove(id: String) = update { idx -> idx.copy(items = idx.items.filterNot { it.id == id }) }
    suspend fun find(id: String): MediaItem? = get().items.firstOrNull { it.id == id }
}
