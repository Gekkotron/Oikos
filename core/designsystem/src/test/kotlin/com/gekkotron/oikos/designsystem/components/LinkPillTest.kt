package com.gekkotron.oikos.designsystem.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class LinkPillTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `clickable pill reports clicks`() {
        var clicks = 0
        compose.setContent { OikosTheme(ThemeChoice.NIGHT_GLASS) { LinkPill(LinkState.Connected(TransportMode.GATEWAY), onClick = { clicks++ }) } }
        compose.onNodeWithText("Gateway").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `pill without onClick has no click action`() {
        compose.setContent { OikosTheme(ThemeChoice.NIGHT_GLASS) { LinkPill(LinkState.Connecting, onClick = null) } }
        compose.onNodeWithText("Connecting…").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }
}
