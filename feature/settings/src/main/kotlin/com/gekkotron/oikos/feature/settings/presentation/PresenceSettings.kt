package com.gekkotron.oikos.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.data.AppPreferences
import com.gekkotron.oikos.data.PresenceConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the Presence section renders. [home] is `(lat, lng)` or null when no home has been picked yet. */
data class PresenceSettingsUi(
    val enabled: Boolean = false,
    val alias: String = "",
    val home: Pair<Double, Double>? = null,
    val radiusM: Int = 150,
)

@HiltViewModel
class PresenceSettingsViewModel internal constructor(
    private val preferences: AppPreferences,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) : ViewModel() {
    @Inject
    constructor(preferences: AppPreferences) : this(preferences, scope = NullScope, Dispatchers.Default)

    private val outerScope: CoroutineScope get() = if (scope === NullScope) viewModelScope else scope

    val state: StateFlow<PresenceSettingsUi> = preferences.presence
        .map { it?.toUi() ?: PresenceSettingsUi() }
        .stateIn(outerScope, SharingStarted.WhileSubscribed(5_000), preferences.presence.value?.toUi() ?: PresenceSettingsUi())

    fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }
    fun setAlias(alias: String) = update { it.copy(alias = alias) }
    fun setHome(lat: Double, lng: Double) = update { it.copy(homeLat = lat, homeLng = lng) }
    fun setRadius(radiusM: Int) = update { it.copy(radiusM = radiusM) }

    private fun update(change: (PresenceConfig) -> PresenceConfig) {
        outerScope.launch(dispatcher) {
            val current = preferences.presence.value ?: PresenceConfig()
            preferences.setPresence(change(current))
        }
    }

    private companion object {
        /** Sentinel so the Hilt-visible constructor can defer to [viewModelScope] without exposing it as a parameter. */
        val NullScope = CoroutineScope(Dispatchers.Unconfined)
    }
}

private fun PresenceConfig.toUi(): PresenceSettingsUi {
    val lat = homeLat
    val lng = homeLng
    return PresenceSettingsUi(
        enabled = enabled,
        alias = alias,
        home = if (lat != null && lng != null) lat to lng else null,
        radiusM = radiusM,
    )
}
