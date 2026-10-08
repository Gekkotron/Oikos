package com.gekkotron.oikos.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.data.AppPreferences
import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.FrigateConfig
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.data.ThemePreference
import com.gekkotron.oikos.data.normalizeFrigateUrl
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

    /**
     * Blank [url] removes Frigate; otherwise it must be `http(s)://host[:port]` (Frigate auth spec §3). A blank [password]
     * keeps the stored one, but only for the same user; no user means no login.
     */
    fun setFrigate(url: String, user: String, password: String): Boolean {
        if (url.isBlank()) {
            viewModelScope.launch { profiles.setFrigate(null) }
            return true
        }
        val normalized = normalizeFrigateUrl(url) ?: return false
        val username = user.trim().ifEmpty { null }
        val old = ((profiles.state.value as? ProfileState.Ready)?.profile as? ConnectionProfile.Direct)?.frigate
        val pass = when {
            username == null -> null
            password.isNotEmpty() -> password
            old?.username == username -> old.password
            else -> null
        }
        viewModelScope.launch { profiles.setFrigate(FrigateConfig(normalized, username, pass)) }
        return true
    }

    /** "Register again" in the push sheet (push alerts spec §7.4). */
    fun registerAgain() = push.registerAgain()

    /** The notification permission may have changed while the app was away. */
    fun refreshPush() = push.refresh()
}
