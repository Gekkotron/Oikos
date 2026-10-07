package com.gekkotron.oikos.feature.alerts.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.alerts.AlertChannel
import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.sync.HistorySync
import com.gekkotron.oikos.push.PushStatus
import com.gekkotron.oikos.push.PushStatusSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class RecentAlertsUi(val alerts: List<Alert> = emptyList(), val notificationsOff: Boolean = false)

/** The Security tab's section: the five newest alarm, security and camera alerts (push alerts spec §7.1). */
@HiltViewModel
class RecentAlertsViewModel @Inject constructor(
    repository: AlertRepository,
    private val status: PushStatusSource,
    private val history: HistorySync,
) : ViewModel() {
    val state: StateFlow<RecentAlertsUi> = combine(repository.recent(CHANNELS, LIMIT), status.status) { alerts, push ->
        RecentAlertsUi(alerts, push == PushStatus.NotificationsOff)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecentAlertsUi())

    /** Alerts that arrived through the retained history and have not been announced yet. */
    val catchUp: StateFlow<Int> = history.catchUp

    fun acknowledgeCatchUp() = history.acknowledge()

    /** The permission may have changed in the system settings while the app was away. */
    fun refreshPermission() = status.refresh()

    private companion object {
        val CHANNELS = setOf(AlertChannel.ALARM, AlertChannel.SECURITY, AlertChannel.CAMERA)
        const val LIMIT = 5
    }
}
