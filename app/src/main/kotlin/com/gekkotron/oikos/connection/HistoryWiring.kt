package com.gekkotron.oikos.connection

import com.gekkotron.oikos.alerts.PushTopics
import com.gekkotron.oikos.alerts.sync.HistorySync
import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.ProfileState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The history topic is subscribed while a real (direct) profile is live; the demo has no OikosPush (spec §6.2, §7.6). */
internal fun historyFilters(state: ProfileState): Set<String> =
    if ((state as? ProfileState.Ready)?.profile is ConnectionProfile.Direct) setOf(PushTopics.HISTORY) else emptySet()

class HistoryWiring(
    private val states: Flow<ProfileState>,
    private val subscriptions: SharedSubscriptions,
    private val sync: HistorySync,
    private val scope: CoroutineScope,
) {
    fun start() {
        sync.start(scope)
        scope.launch { states.map(::historyFilters).distinctUntilChanged().collect { subscriptions.setExtra(it) } }
    }
}
