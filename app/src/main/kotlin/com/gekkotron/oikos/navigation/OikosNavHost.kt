package com.gekkotron.oikos.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.gekkotron.oikos.feature.energy.ui.EnergyRoute
import com.gekkotron.oikos.feature.home.rooms.RoomsRoute
import com.gekkotron.oikos.feature.home.ui.HomeRoute
import com.gekkotron.oikos.feature.onboarding.ui.OnboardingRoute
import kotlinx.serialization.Serializable

@Serializable
data object OnboardingKey : NavKey

@Serializable
data object HomeKey : NavKey

@Serializable
data object RoomsKey : NavKey

@Serializable
data object EnergyKey : NavKey

/** The saved profile picks the root: Ready → Home; none or locked → onboarding (spec §2). */
@Composable
fun OikosNavHost(viewModel: AppViewModel = hiltViewModel()) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val route by viewModel.route.collectAsStateWithLifecycle()
    val root: NavKey = when (val p = profile) {
        ProfileState.Loading -> return // a few milliseconds at launch; the background is already drawn
        // A direct profile that was just set up first gets the Rooms step (spec §5.2 step 5), on the live source.
        is ProfileState.Ready -> if (p.roomsPending) RoomsKey else HomeKey
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
    val top = backStack.lastOrNull()
    val tabs = showsTabBar(top)
    // The bar owns the bottom inset on the main tabs; their own Scaffolds then only pad top and sides.
    val tabInsets = ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        bottomBar = { if (tabs) MainTabBar(selected = top, onSelect = { selectTab(backStack, it) }) },
    ) { padding ->
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
            onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<OnboardingKey> { OnboardingRoute() }
                entry<RoomsKey> {
                    RoomsRoute(onDone = {
                        // First run: finishing flips the root to Home, which replaces this entry. From Home: pop back.
                        viewModel.finishRooms()
                        if (backStack.size > 1) backStack.removeLastOrNull()
                    })
                }
                entry<HomeKey> {
                    HomeRoute(
                        onSetUpAgain = viewModel::setUpAgain,
                        onAssignRooms = { if (backStack.lastOrNull() != RoomsKey) backStack.add(RoomsKey) },
                        route = route,
                        onRouteChange = viewModel::setRoute,
                        windowInsets = tabInsets,
                    )
                }
                entry<EnergyKey> {
                    EnergyRoute(onOpenHome = { selectTab(backStack, HomeKey) }, windowInsets = tabInsets)
                }
            },
        )
    }
}
