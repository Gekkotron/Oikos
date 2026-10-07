package com.gekkotron.oikos.feature.alerts.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.alerts.AlertChannel
import com.gekkotron.oikos.alerts.AlertRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/** [loading] is true only before the first read, so the empty state never flashes. */
data class AlertsUi(
    val filter: AlertChannel? = null,
    val sections: List<DaySection> = emptyList(),
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AlertsViewModel internal constructor(
    private val repository: AlertRepository,
    private val clock: () -> Instant,
    private val zone: ZoneId,
) : ViewModel() {
    @Inject
    constructor(repository: AlertRepository) : this(repository, Instant::now, ZoneId.systemDefault())

    private val filter = MutableStateFlow<AlertChannel?>(null)

    val state: StateFlow<AlertsUi> = filter
        .flatMapLatest { channel ->
            repository.all(channel).map { alerts ->
                AlertsUi(channel, groupByDay(alerts, zone, clock().atZone(zone).toLocalDate()), loading = false)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertsUi())

    fun select(channel: AlertChannel?) {
        filter.value = channel
    }

    /** "Clear all" on this phone only (spec §7.2). */
    fun clearAll() {
        viewModelScope.launch { repository.clearAll() }
    }
}
