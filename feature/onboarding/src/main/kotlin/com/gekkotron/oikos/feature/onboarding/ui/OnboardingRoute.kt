package com.gekkotron.oikos.feature.onboarding.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.feature.onboarding.OnboardingViewModel
import com.gekkotron.oikos.feature.onboarding.Step

/** The whole onboarding flow. It ends when a profile is saved; the app then shows Home. */
@Composable
fun OnboardingRoute(viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.step != Step.WELCOME) { viewModel.back() }
    when (state.step) {
        Step.WELCOME -> WelcomeScreen(state.locked, state.saving, viewModel::openScan, viewModel::openManual, viewModel::tryDemo, state.saveFailed)
        Step.SCAN -> ScanScreen(state.scanError, viewModel::onQrScanned, viewModel::openManual, onBack = { viewModel.back() })
        Step.MANUAL -> ManualScreen(state.form, state.formErrors, viewModel::updateForm, viewModel::submitManual, onBack = { viewModel.back() })
        Step.CONNECT -> ConnectScreen(state.test, state.saving, viewModel::retry, onBack = { viewModel.back() }, onContinue = viewModel::finish, saveFailed = state.saveFailed)
    }
}
