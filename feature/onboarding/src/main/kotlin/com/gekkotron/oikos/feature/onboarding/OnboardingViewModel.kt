package com.gekkotron.oikos.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.OnboardingPayload
import com.gekkotron.oikos.data.ParseResult
import com.gekkotron.oikos.data.PayloadError
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.ProfileState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class Step { WELCOME, SCAN, MANUAL, CONNECT }

data class OnboardingUiState(
    val step: Step = Step.WELCOME,
    val locked: Boolean = false,
    val scanError: PayloadError? = null,
    val form: ManualForm = ManualForm(),
    val showFormErrors: Boolean = false,
    val test: TestState = TestState.Connecting,
    val saving: Boolean = false,
    /** The profile couldn't be saved (a Keystore or disk failure); the user can try again. */
    val saveFailed: Boolean = false,
) {
    val formErrors: Set<FormField> get() = if (showFormErrors) form.errors() else emptySet()
}

/**
 * Welcome → Scan | Manual → Connect & discover (spec §5). The draft profile, with its secrets, lives
 * only in this ViewModel, never in saved state. Saving a profile makes the app switch to Home.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val tester: ConnectionTester,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = combine(_state, profiles.state) { s, p -> s.copy(locked = p is ProfileState.Locked) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, OnboardingUiState())

    private var draft: ConnectionProfile.Direct? = null
    private var origin = Step.WELCOME
    private var testJob: Job? = null
    private var lastRejectedScan: String? = null

    fun openScan() = go(Step.SCAN)

    fun openManual() = go(Step.MANUAL)

    fun tryDemo() {
        viewModelScope.launch { save(ConnectionProfile.Demo) }
    }

    /** Called for every decoded QR frame; the camera keeps reporting the same code, so repeats are ignored. */
    fun onQrScanned(text: String) {
        if (_state.value.step != Step.SCAN || text == lastRejectedScan) return
        when (val result = OnboardingPayload.parse(text)) {
            is ParseResult.Ok -> {
                lastRejectedScan = null
                connect(result.profile, from = Step.SCAN)
            }
            is ParseResult.Error -> {
                lastRejectedScan = text
                _state.update { it.copy(scanError = result.error) }
            }
        }
    }

    fun updateForm(form: ManualForm) = _state.update { it.copy(form = form) }

    fun submitManual() {
        val form = _state.value.form
        if (form.errors().isNotEmpty()) {
            _state.update { it.copy(showFormErrors = true) }
            return
        }
        connect(form.toProfile(), from = Step.MANUAL)
    }

    fun retry() {
        draft?.let(::startTest)
    }

    fun finish() {
        val profile = draft ?: return
        if (_state.value.test !is TestState.Connected) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true, saveFailed = false) }
            // Close the test connection first: it must not overlap the app's own connection.
            testJob?.cancelAndJoin()
            save(profile)
        }
    }

    /** @return false when there is nothing to go back to, so the system handles Back. */
    fun back(): Boolean = when (_state.value.step) {
        Step.WELCOME -> false
        Step.CONNECT -> {
            testJob?.cancel()
            testJob = null
            draft = null
            go(origin)
            true
        }
        Step.SCAN, Step.MANUAL -> {
            go(Step.WELCOME)
            true
        }
    }

    private fun go(step: Step) {
        if (step == Step.SCAN) lastRejectedScan = null
        _state.update { it.copy(step = step, scanError = null, saveFailed = false) }
    }

    /**
     * Saving encrypts the secrets (Keystore) and writes the file; either can fail. A failure shows an
     * error and re-enables the buttons instead of crashing. On success the app switches to Home.
     */
    private suspend fun save(profile: ConnectionProfile) {
        _state.update { it.copy(saving = true, saveFailed = false) }
        try {
            profiles.save(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(saving = false, saveFailed = true) }
        }
    }

    private fun connect(profile: ConnectionProfile.Direct, from: Step) {
        origin = from
        draft = profile
        _state.update { it.copy(step = Step.CONNECT, scanError = null, saveFailed = false) }
        startTest(profile)
    }

    private fun startTest(profile: ConnectionProfile.Direct) {
        testJob?.cancel()
        _state.update { it.copy(test = TestState.Connecting) }
        testJob = viewModelScope.launch {
            tester.test(profile).collect { result -> _state.update { it.copy(test = result) } }
        }
    }
}
