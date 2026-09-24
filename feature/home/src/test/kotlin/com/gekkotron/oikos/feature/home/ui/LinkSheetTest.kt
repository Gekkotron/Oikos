package com.gekkotron.oikos.feature.home.ui

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
    fun `reconnecting shows the attempt`() {
        show(LinkState.Reconnecting(3))
        compose.onNodeWithText("Reconnecting…").performClick()
        compose.onNodeWithText("Reconnecting (attempt 3)", substring = true).assertExists()
    }
}
