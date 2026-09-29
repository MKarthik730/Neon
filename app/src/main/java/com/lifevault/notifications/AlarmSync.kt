package com.lifevault.notifications

import com.lifevault.domain.notify.AlarmPlanner
import com.lifevault.vault.VaultSession
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * While the vault is unlocked, rebuilds the alarm plan whenever the timetable, holidays, settings,
 * events or rules change (and once on unlock). Only times/types/ids leave the vault.
 */
class AlarmSync(private val session: VaultSession, private val scheduler: AlarmScheduler) {

    @OptIn(FlowPreview::class)
    fun start() {
        session.scope.launch {
            combine(
                session.settings.flow,
                session.timetable.flow,
                session.holidays.flow,
                session.events.flow,
                session.rules.flow,
            ) { settings, timetable, holidays, events, rules ->
                AlarmPlanner.plan(LocalDateTime.now(), timetable, holidays, settings, events.events, rules)
            }
                .debounce(400)
                .collect { plan -> runCatching { scheduler.setPlan(plan) } }
        }
    }
}
