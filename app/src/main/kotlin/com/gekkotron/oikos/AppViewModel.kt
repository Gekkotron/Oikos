package com.gekkotron.oikos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.connection.EntitySourceSession
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.ProfileState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val sources: EntitySourceSession,
) : ViewModel() {
    val profile: StateFlow<ProfileState> = profiles.state

    /**
     * Forgets the profile. The transport closes, the discovery source is dropped (the next profile may
     * be an equal one, such as the demo again, and must still start from an empty house), and the app
     * returns to onboarding.
     */
    /** The Rooms step was finished or skipped (a no-op when it was opened from Home). */
    fun finishRooms() {
        viewModelScope.launch { profiles.finishRooms() }
    }

    fun setUpAgain() {
        viewModelScope.launch {
            profiles.clear()
            sources.reset()
        }
    }
}
