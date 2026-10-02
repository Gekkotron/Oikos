package com.gekkotron.oikos.designsystem.alarm

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Command
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class AlarmControlsTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<Command>()
    private val caps = Capabilities(
        alarmModes = setOf(AlarmMode.HOME, AlarmMode.AWAY, AlarmMode.NIGHT),
        codeArmRequired = false,
        codeDisarmRequired = true,
    )

    private fun hero(state: AlarmState?, available: Boolean = true) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) { AlarmHero("Alarm", state, available, pending = false, caps = caps, onSend = { sent += it }) }
    }

    @Test
    fun `a stale hero exposes the last known value`() {
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                AlarmHero("Alarm", AlarmState.DISARMED, true, pending = false, stale = true, caps = caps, onSend = { sent += it })
            }
        }
        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Last known value") and hasText("Alarm"),
        ).assertExists()
    }

    @Test
    fun `a live hero has no state description`() {
        hero(AlarmState.DISARMED)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)).assertDoesNotExist()
    }

    @Test
    fun `arming without a required code sends at once`() {
        hero(AlarmState.DISARMED)
        compose.onNodeWithText("Away").performClick()
        assertEquals(listOf<Command>(Command.Arm(AlarmMode.AWAY)), sent)
    }

    @Test
    fun `disarm asks for the code and sends it`() {
        hero(AlarmState.ARMED_AWAY)
        compose.onNodeWithText("Disarm").performClick()
        compose.onNodeWithText("1").performClick()
        compose.onNodeWithText("2").performClick()
        compose.onNodeWithText("3").performClick()
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.onNodeWithText("4").performClick()
        compose.onNodeWithText("OK").performClick()
        assertEquals(listOf<Command>(Command.Disarm("124")), sent)
    }

    @Test
    fun `the keypad starts empty after a send`() {
        hero(AlarmState.ARMED_AWAY)
        compose.onNodeWithText("Disarm").performClick()
        compose.onNodeWithText("7").performClick()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText("Disarm").performClick()
        compose.onNodeWithTag("keypad-dots").assertTextEquals("")
        compose.onNodeWithText("OK").assertIsNotEnabled()
    }

    @Test
    fun `triggered shows the alarm and only disarm`() {
        hero(AlarmState.TRIGGERED)
        compose.onNodeWithText("Triggered!").assertExists()
        compose.onNodeWithText("Disarm").assertExists()
        compose.onNodeWithText("Away").assertDoesNotExist()
    }

    @Test
    fun `delays are spelled out`() {
        hero(AlarmState.PENDING)
        compose.onNodeWithText("Entry delay…").assertExists()
    }

    @Test
    fun `unknown state offers disabled buttons`() {
        hero(null)
        compose.onNodeWithText("—").assertExists()
        compose.onNodeWithText("Away").assertIsNotEnabled()
        compose.onNodeWithText("Disarm").assertIsNotEnabled()
    }

    @Test
    fun `unavailable shows it and disables the buttons`() {
        hero(AlarmState.DISARMED, available = false)
        compose.onNodeWithText("Unavailable").assertExists()
        compose.onNodeWithText("Home").assertIsNotEnabled()
    }
}
