package com.gekkotron.oikos.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.PowerUnits
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.source.EntitySource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Live decorations for the tab bar: an armed shield, a producing bolt (nav icon states §A). */
data class TabStatus(val alarmArmed: Boolean = false, val solarActive: Boolean = false)

@HiltViewModel
class MainTabsViewModel @Inject constructor(source: EntitySource) : ViewModel() {
    val status: StateFlow<TabStatus> = combine(source.entities, source.states()) { entities, states ->
        TabStatus(alarmArmed = alarmArmed(entities, states), solarActive = solarActive(entities, states))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TabStatus())
}

/** Any alarm panel in an active armed/triggered state lights the shield. */
internal fun alarmArmed(entities: Map<String, Entity>, states: Map<String, EntityState>): Boolean =
    entities.values.any { e ->
        e.kind == Kind.ALARM_PANEL &&
            ((states[e.uniqueId]?.value as? StateValue.Alarm)?.state in ACTIVE_ALARM_STATES)
    }

internal val ACTIVE_ALARM_STATES: Set<AlarmState> = setOf(
    AlarmState.ARMED_HOME, AlarmState.ARMED_AWAY, AlarmState.ARMED_NIGHT,
    AlarmState.ARMED_VACATION, AlarmState.ARMED_CUSTOM_BYPASS,
    AlarmState.ARMING, AlarmState.PENDING, AlarmState.TRIGGERED,
)

/** The IDLE threshold EnergyFlowBuilder uses to call the house "idle"; small enough to light up
 *  as soon as a panel starts producing but not from inverter standby noise. */
internal const val SOLAR_IDLE_W: Double = 10.0

internal fun solarActive(entities: Map<String, Entity>, states: Map<String, EntityState>): Boolean {
    val solars = entities.values.filter { it.ui.energyRole == EnergyRole.SOLAR }
    if (solars.isEmpty()) return false
    val total = solars.sumOf { e ->
        val numeric = (states[e.uniqueId]?.value as? StateValue.Numeric)?.value ?: return@sumOf 0.0
        val factor = PowerUnits.factor(e.unit) ?: return@sumOf 0.0
        val signed = numeric * factor
        if (signed > 0.0) signed else 0.0
    }
    return total > SOLAR_IDLE_W
}
