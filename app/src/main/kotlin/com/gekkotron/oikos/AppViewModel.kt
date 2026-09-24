package com.gekkotron.oikos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.ProfileState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(private val profiles: ProfileRepository) : ViewModel() {
    val profile: StateFlow<ProfileState> = profiles.state

    /** Forgets the profile. The transport closes and the app returns to onboarding. */
    fun setUpAgain() {
        viewModelScope.launch { profiles.clear() }
    }
}
