package com.gekkotron.oikos.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.data.AppPreferences
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.ThemePreference
import com.gekkotron.oikos.data.normalizeHistoryUrl
import com.gekkotron.oikos.push.PushStatus
import com.gekkotron.oikos.push.PushStatusSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUi(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val connection: ConnectionSummary? = null,
    val push: PushStatus = PushStatus.Waiting,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val preferences: AppPreferences,
    private val push: PushStatusSource,
) : ViewModel() {
    val state: StateFlow<SettingsUi> = combine(preferences.theme, profiles.state, push.status) { theme, profile, status ->
        SettingsUi(theme ?: ThemePreference.SYSTEM, ConnectionSummary.from(profile), status)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        // Seeded from the live values so the radio does not show System for a frame.
        SettingsUi(preferences.theme.value ?: ThemePreference.SYSTEM, ConnectionSummary.from(profiles.state.value), push.status.value),
    )

    fun setTheme(theme: ThemePreference) {
        viewModelScope.launch { preferences.setTheme(theme) }
    }

    /** Blank clears; anything else must be a history URL (history spec §4.4). */
    fun setHistoryUrl(text: String): Boolean {
        val url = if (text.isBlank()) null else normalizeHistoryUrl(text) ?: return false
        viewModelScope.launch { profiles.setHistoryUrl(url) }
        return true
    }

    /** "Register again" in the push sheet (push alerts spec §7.4). */
    fun registerAgain() = push.registerAgain()

    /** The notification permission may have changed while the app was away. */
    fun refreshPush() = push.refresh()
}
