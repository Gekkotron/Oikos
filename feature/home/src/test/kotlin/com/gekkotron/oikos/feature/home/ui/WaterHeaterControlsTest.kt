package com.gekkotron.oikos.feature.home.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.home.presentation.CardUi
import com.gekkotron.oikos.feature.home.presentation.HomeUiBuilder
import com.gekkotron.oikos.feature.home.presentation.RoomFilter
import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.transport.LinkState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

/** Water heater view spec §6: the sheet shows only the controls the heater has. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class WaterHeaterControlsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun card(climate: StateValue.Climate, modes: List<String>, numbers: List<String>): CardUi {
        val wh = testEntity(
            "wh", Kind.WATER_HEATER, "Water heater", deviceId = "dev",
            capabilities = Capabilities(hvacModes = modes, minTemp = 40.0, maxTemp = 65.0, tempStep = 1.0),
        )
        val companions = numbers.mapIndexed { i, name ->
            testEntity("n$i", Kind.NUMBER, name, deviceId = "dev", capabilities = Capabilities(min = 0.0, max = 100.0, step = 1.0))
        }
        val entities = (listOf(wh) + companions).associateBy { it.uniqueId }
        return HomeUiBuilder.build(
            entities, mapOf("wh" to EntityState("wh", climate)), emptyList(), RoomFilter.All, LinkState.Connecting, Locale.US,
        ).card("wh")!!
    }

    private fun show(card: CardUi) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) { Column { WaterHeaterControls(card, onSend = {}, onSendCompanion = { _, _ -> }) } }
    }

    @Test
    fun `no modes and no target shows only the power numbers`() {
        show(card(StateValue.Climate("heat", null, 58.0), modes = emptyList(), numbers = listOf("Power 1", "Power 2", "Power 3")))
        compose.onNodeWithText("Target").assertDoesNotExist()
        compose.onNodeWithText("Heating").assertDoesNotExist()
        compose.onNodeWithText("Power 1").assertExists()
        compose.onNodeWithText("Power 3").assertExists()
    }

    @Test
    fun `jeedom heater without a target keeps its mode chips`() {
        show(card(StateValue.Climate("eco", null, 52.0), modes = listOf("off", "eco", "performance"), numbers = listOf("Force power")))
        compose.onNodeWithText("Eco").assertExists()
        compose.onNodeWithText("Force").assertExists()
        compose.onNodeWithText("Target").assertDoesNotExist()
    }

    @Test
    fun `jeedom heater with a target shows the stepper`() {
        show(card(StateValue.Climate("eco", 60.0, 52.0), modes = listOf("off", "eco", "performance"), numbers = emptyList()))
        compose.onNodeWithText("Target").assertExists()
        compose.onNodeWithText("60°").assertExists()
    }
}
