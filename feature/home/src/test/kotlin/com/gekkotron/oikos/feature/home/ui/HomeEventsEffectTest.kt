package com.gekkotron.oikos.feature.home.ui

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.home.presentation.HomeEvent
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.Kind
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class HomeEventsEffectTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 4)
    private val retried = mutableListOf<Pair<String, Command>>()
    private val retriedGroups = mutableListOf<Triple<String, Command, List<String>>>()

    private fun show() = compose.setContent {
        OikosTheme {
            val host = remember { SnackbarHostState() }
            HomeEventsEffect(
                events, host,
                onRetry = { id, c -> retried += id to c },
                onRetryGroup = { g, c, ids -> retriedGroups += Triple(g, c, ids) },
            )
            SnackbarHost(host)
        }
    }

    @Test
    fun `not confirmed shows a snackbar whose Retry resends`() {
        show()
        compose.waitForIdle()
        events.tryEmit(HomeEvent.NotConfirmed("lamp", Command.Toggle))
        compose.onNodeWithText("Not confirmed").assertExists()
        compose.onNodeWithText("Retry").performClick()
        compose.waitForIdle()
        assertEquals(listOf<Pair<String, Command>>("lamp" to Command.Toggle), retried)
    }

    @Test
    fun `rejected shows the reason`() {
        show()
        compose.waitForIdle()
        events.tryEmit(HomeEvent.Rejected("plug", "unavailable"))
        compose.onNodeWithText("Command refused: unavailable").assertExists()
    }

    @Test
    fun `a merged group failure shows one snackbar and Retry resends to the failed members`() {
        show()
        compose.waitForIdle()
        events.tryEmit(HomeEvent.GroupNotConfirmed("group:living:cover", Kind.COVER, listOf("a", "c"), listOf("Bay", "Patio"), Command.Close))
        compose.onNodeWithText("2 shutters didn't respond").assertExists()
        compose.onNodeWithText("Retry").performClick()
        compose.waitForIdle()
        assertEquals(listOf(Triple<String, Command, List<String>>("group:living:cover", Command.Close, listOf("a", "c"))), retriedGroups)
    }

    @Test
    fun `a single failed member is named`() {
        show()
        compose.waitForIdle()
        events.tryEmit(HomeEvent.GroupNotConfirmed("group:living:light", Kind.LIGHT, listOf("x"), listOf("Ceiling"), Command.TurnOff))
        compose.onNodeWithText("Ceiling didn't respond").assertExists()
    }
}
