package com.gekkotron.oikos.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.gekkotron.oikos.AppViewModel
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.feature.home.ui.HomeRoute
import com.gekkotron.oikos.feature.onboarding.ui.OnboardingRoute
import kotlinx.serialization.Serializable

@Serializable
data object OnboardingKey : NavKey

@Serializable
data object HomeKey : NavKey

/** The saved profile picks the root: Ready → Home; none or locked → onboarding (spec §2). */
@Composable
fun OikosNavHost(viewModel: AppViewModel = hiltViewModel()) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val root: NavKey = when (profile) {
        ProfileState.Loading -> return // a few milliseconds at launch; the background is already drawn
        is ProfileState.Ready -> HomeKey
        ProfileState.None, ProfileState.Locked -> OnboardingKey
    }
    // One back stack for the app's lifetime. A new root (onboarding finished, or "set up again")
    // replaces the entries inside it, so NavDisplay pops the old ones and clears their ViewModel
    // stores. Disposing the whole NavDisplay instead (key(root)) never pops anything, and the stale
    // ViewModels come back the next time the same root is shown.
    val backStack = rememberNavBackStack(root)
    LaunchedEffect(root) {
        if (backStack.toList() != listOf(root)) {
            backStack.add(root)
            backStack.removeAll { it != root }
        }
    }
    NavDisplay(
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<OnboardingKey> { OnboardingRoute() }
            entry<HomeKey> { HomeRoute(onSetUpAgain = viewModel::setUpAgain) }
        },
    )
}
