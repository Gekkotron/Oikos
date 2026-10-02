package com.gekkotron.oikos.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.data.AppPreferences
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.ThemePreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUi(val theme: ThemePreference = ThemePreference.SYSTEM, val connection: ConnectionSummary? = null)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    profiles: ProfileRepository,
    private val preferences: AppPreferences,
) : ViewModel() {
    val state: StateFlow<SettingsUi> = combine(preferences.theme, profiles.state) { theme, profile ->
        SettingsUi(theme ?: ThemePreference.SYSTEM, ConnectionSummary.from(profile))
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        // Seeded from the live values so the radio does not show System for a frame.
        SettingsUi(preferences.theme.value ?: ThemePreference.SYSTEM, ConnectionSummary.from(profiles.state.value)),
    )

    fun setTheme(theme: ThemePreference) {
        viewModelScope.launch { preferences.setTheme(theme) }
    }
}
