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

    private fun show() = compose.setContent {
        OikosTheme {
            val host = remember { SnackbarHostState() }
            HomeEventsEffect(events, host) { id, c -> retried += id to c }
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
}
