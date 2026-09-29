package com.lifevault.notifications

import com.lifevault.domain.attendance.Schedule
import com.lifevault.vault.VaultSession

/** Moves Present/Absent taps made from notifications (while locked) into the encrypted attendance files. */
object PendingMerge {
    /**
     * Applies every queued action to [session], then removes exactly those actions from [queue].
     * Returns how many were merged. If this is interrupted (vault locked, error), nothing is removed,
     * so the actions are retried on the next unlock.
     */
    suspend fun merge(session: VaultSession, queue: PendingActionsQueue): Int {
        val actions = queue.peek()
        if (actions.isEmpty()) return 0
        val schedule = Schedule(session.timetable.get(), session.holidays.get())
        for (a in actions) {
            val subjects = schedule.session(a.date, a.session)?.subjects ?: emptyList()
            session.attendance.mark(a.date, a.session, a.status, subjects)
        }
        queue.remove(actions)
        return actions.size
    }
}
