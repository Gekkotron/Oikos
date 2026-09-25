package com.gekkotron.oikos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.connection.EntitySourceSession
import com.gekkotron.oikos.connection.routeChoice
import com.gekkotron.oikos.connection.toPreference
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.transport.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val sources: EntitySourceSession,
) : ViewModel() {
    val profile: StateFlow<ProfileState> = profiles.state

    /** The link sheet's route row; null hides it (no gateway in the profile). */
    val route: StateFlow<Route?> =
        combine(profiles.state, profiles.route, ::routeChoice).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The Rooms step was finished or skipped (a no-op when it was opened from Home). */
    fun finishRooms() {
        viewModelScope.launch { profiles.finishRooms() }
    }

    /** Rebuilds the transport for the new route; the discovery source and the house on screen stay. */
    fun setRoute(route: Route) {
        viewModelScope.launch { profiles.setRoute(route.toPreference()) }
    }

    /**
     * Forgets the profile. The transport closes, the discovery source is dropped (the next profile may
     * be an equal one, such as the demo again, and must still start from an empty house), and the app
     * returns to onboarding.
     */
    fun setUpAgain() {
        viewModelScope.launch {
            profiles.clear()
            sources.reset()
        }
    }
}
