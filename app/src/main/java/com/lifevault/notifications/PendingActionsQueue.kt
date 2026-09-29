package com.lifevault.notifications

import com.lifevault.domain.model.AttendanceStatus
import com.lifevault.domain.model.Session
import com.lifevault.domain.serial.LocalDateSerializer
import com.lifevault.domain.util.VaultJson
import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalDate

/**
 * One attendance mark made from a notification while the vault was locked.
 * Deliberately minimal: date, session and Present/Absent. No subject, note, mood or anything else.
 */
@Serializable
data class PendingAttendanceAction(
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    val session: Session,
    val status: AttendanceStatus,
    val at: Long,
)

@Serializable
data class PendingQueueFile(val version: Int = 1, val actions: List<PendingAttendanceAction> = emptyList())

/**
 * The only vault-related data ever written outside the vault, and only when the user enables
 * "Mark from notification without unlocking". It lives in app-private storage (excluded from backups)
 * and is merged into the encrypted attendance files, then cleared, on the next unlock.
 */
class PendingActionsQueue(private val file: File) {

    @Synchronized
    fun append(action: PendingAttendanceAction) {
        require(action.status == AttendanceStatus.PRESENT || action.status == AttendanceStatus.ABSENT)
        val current = read().actions.filterNot { it.date == action.date && it.session == action.session }
        write(PendingQueueFile(actions = current + action))
    }

    @Synchronized
    fun peek(): List<PendingAttendanceAction> = read().actions

    /** Removes exactly the actions that were merged, keeping any that arrived meanwhile. */
    @Synchronized
    fun remove(merged: Collection<PendingAttendanceAction>) {
        val left = read().actions.filterNot { it in merged }
        if (left.isEmpty()) file.delete() else write(PendingQueueFile(actions = left))
    }

    @Synchronized
    fun clear() { file.delete() }

    private fun read(): PendingQueueFile = try {
        if (file.isFile) VaultJson.decodeFromString(PendingQueueFile.serializer(), file.readText()) else PendingQueueFile()
    } catch (e: Exception) {
        PendingQueueFile()
    }

    private fun write(q: PendingQueueFile) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(VaultJson.encodeToString(PendingQueueFile.serializer(), q))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
