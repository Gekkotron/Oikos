package com.gekkotron.oikos.feature.home.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.home.presentation.HomeUiState
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertTrue
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class LinkSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(link: LinkState, onSetUpAgain: () -> Unit = {}) = compose.setContent {
        OikosTheme { HomeScreen(HomeUiState(loading = false, link = link), {}, {}, { _, _ -> }, onSetUpAgain = onSetUpAgain) }
    }

    @Test
    fun `tapping the pill opens the sheet and set up again calls back`() {
        var called = false
        show(LinkState.Connected(TransportMode.DEMO)) { called = true }
        compose.onNodeWithText("Demo").performClick()
        compose.onNodeWithText("exploring the demo house", substring = true).assertExists()
        compose.onNodeWithText("Set up connection again").performClick()
        assertTrue(called)
    }

    @Test
    fun `rejected credentials explain themselves`() {
        show(LinkState.Offline(LinkState.Offline.REJECTED))
        compose.onNodeWithText("Offline").performClick()
        compose.onNodeWithText("The broker rejected the username or password.").assertExists()
    }

    @Test
    fun `offline reasons are described in words, not as raw constants`() {
        var link by mutableStateOf<LinkState>(LinkState.Offline(LinkState.Offline.CLOSED))
        compose.setContent {
            OikosTheme { HomeScreen(HomeUiState(loading = false, link = link), {}, {}, { _, _ -> }, onSetUpAgain = {}) }
        }
        compose.onNodeWithText("Offline").performClick()
        compose.onNodeWithText("The connection was closed.").assertExists()
        link = LinkState.Offline(LinkState.Offline.FAILED)
        compose.onNodeWithText("Couldn't start the connection.").assertExists()
        link = LinkState.Offline(LinkState.Offline.PAUSED)
        compose.onNodeWithText("Paused while Oikos is in the background.").assertExists()
        link = LinkState.Offline("something new")
        compose.onNodeWithText("Offline.").assertExists()
        compose.onNodeWithText("something new", substring = true).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h914dp")
    fun `offline reasons are translated`() {
        show(LinkState.Offline(LinkState.Offline.CLOSED))
        compose.onNodeWithText("Hors ligne").performClick()
        compose.onNodeWithText("La connexion a été fermée.").assertExists()
        compose.onNodeWithText("closed", substring = true).assertDoesNotExist()
    }

    @Test
    fun `reconnecting shows the attempt`() {
        show(LinkState.Reconnecting(3))
        compose.onNodeWithText("Reconnecting…").performClick()
        compose.onNodeWithText("Reconnecting (attempt 3)", substring = true).assertExists()
    }

    @Test
    fun `status description is announced with polite live region`() {
        show(LinkState.Connected(TransportMode.DIRECT))
        compose.onNodeWithText("Wi-Fi direct").performClick()
        val statusNode = compose.onNodeWithText("Connected directly to your broker.")
        assertEquals(LiveRegionMode.Polite, statusNode.fetchSemanticsNode().config[SemanticsProperties.LiveRegion])
    }

    @Test
    fun `assign rooms calls back and closes the sheet`() {
        var called = false
        compose.setContent {
            OikosTheme { HomeScreen(HomeUiState(loading = false, link = LinkState.Connected(TransportMode.DIRECT)), {}, {}, { _, _ -> }, onAssignRooms = { called = true }) }
        }
        compose.onNodeWithText("Wi-Fi direct").performClick()
        compose.onNodeWithText("Assign rooms").performClick()
        assertTrue(called)
        compose.onNodeWithText("Connected directly to your broker.").assertDoesNotExist()
    }
}
