package com.gekkotron.oikos.feature.security.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.security.alarm
import com.gekkotron.oikos.feature.security.on
import com.gekkotron.oikos.feature.security.panel
import com.gekkotron.oikos.feature.security.sensor
import com.gekkotron.oikos.feature.security.presentation.SecurityUi
import com.gekkotron.oikos.feature.security.presentation.SecurityUiBuilder
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.transport.LinkState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class SecurityScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val hidden = mutableListOf<Pair<String, Boolean>>()

    private fun ui(entities: List<Entity>, states: List<EntityState> = emptyList()): SecurityUi = SecurityUiBuilder.build(
        entities.associateBy { it.uniqueId }, states.associateBy { it.uniqueId }, listOf(Area("garage", "Garage")), LinkState.Connecting,
    )

    private fun show(state: SecurityUi) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) { SecurityScreen(state, onSetHidden = { id, h -> hidden += id to h }) }
    }

    @Test
    fun `panel, summary and sensors`() {
        show(
            ui(
                listOf(panel(), sensor("garage_door", "garage_door", "Garage door", area = "garage"), sensor("motion", "motion", "Corridor"), sensor("door", "door", "Entrance")),
                listOf(alarm("alarm", AlarmState.ARMED_AWAY), on("garage_door", true), on("motion", true), on("door", false)),
            ),
        )
        compose.onNodeWithText("Armed · Away").assertExists()
        compose.onNodeWithText("1 open · 1 motion").assertExists()
        compose.onNodeWithText("Garage door").assertExists()
        compose.onNodeWithText("Closed").performScrollTo().assertExists()
        compose.onNodeWithText("Recent alerts").performScrollTo().assertExists()
    }

    @Test
    fun `all clear and the no-panel card`() {
        show(ui(listOf(sensor("door", "door", "Entrance")), listOf(on("door", false))))
        compose.onNodeWithText("All clear").assertExists()
        compose.onNodeWithText("No alarm panel. Publish an MQTT alarm_control_panel to see it here.").assertExists()
    }

    @Test
    fun `nothing at all shows one empty state`() {
        show(ui(emptyList()))
        compose.onNodeWithText("No alarm panel or security sensor yet. Publish an MQTT alarm_control_panel, or pair door, motion, smoke or leak sensors.").assertExists()
        compose.onNodeWithText("All clear").assertDoesNotExist()
    }

    @Test
    fun `nothing is drawn while loading`() {
        show(SecurityUi())
        compose.onNodeWithText("All clear").assertDoesNotExist()
        compose.onNodeWithText("Recent alerts").assertDoesNotExist()
    }

    @Test
    fun `tapping a row lets it leave Security`() {
        show(ui(listOf(sensor("mailbox", "door", "Mailbox")), listOf(on("mailbox", false))))
        compose.onNodeWithText("Mailbox").performClick()
        compose.onNodeWithText("Show on Security").performClick()
        assertEquals(listOf("mailbox" to true), hidden)
    }

    @Test
    fun `unavailable and unknown sensors say so`() {
        show(ui(listOf(sensor("a", "smoke", "Kitchen smoke"), sensor("b", "moisture", "Pool")), listOf(on("a", false, available = false))))
        compose.onNodeWithText("Unavailable").assertExists()
        compose.onNodeWithText("—").assertExists()
    }
}
