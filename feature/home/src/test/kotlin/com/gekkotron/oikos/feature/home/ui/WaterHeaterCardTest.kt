package com.gekkotron.oikos.feature.home.ui

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.home.presentation.CardUi
import com.gekkotron.oikos.feature.home.presentation.HomeUiBuilder
import com.gekkotron.oikos.feature.home.presentation.RoomFilter
import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.transport.LinkState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Water heater view spec §5: the Oikos-os card (tank temperature, activity chip, inlet line). */
@RunWith(AndroidJUnit4::class)
class WaterHeaterCardTest {
    @get:Rule
    val compose = createComposeRule()

    private fun card(climate: StateValue.Climate, attributes: Map<String, Any?> = emptyMap(), modes: List<String> = emptyList()): CardUi {
        val wh = testEntity(
            "wh", Kind.WATER_HEATER, "Water heater", area = "garage", unit = "°C",
            capabilities = Capabilities(hvacModes = modes, minTemp = 40.0, maxTemp = 65.0, tempStep = 1.0),
        )
        return HomeUiBuilder.build(
            mapOf("wh" to wh), mapOf("wh" to EntityState("wh", climate, attributes)),
            listOf(Area("garage", "Garage")), RoomFilter.All, LinkState.Connecting, Locale.US,
        ).card("wh")!!
    }

    private fun show(card: CardUi) =
        compose.setContent { OikosTheme(ThemeChoice.NIGHT_GLASS) { EntityCard(card, onClick = {}, onLongClick = {}) } }

    @Test
    fun `heating card shows tank temperature, chip and inlet`() {
        show(card(StateValue.Climate("heat", null, 58.0), mapOf("power" to 43L, "inlet_temperature" to 17.9)))
        compose.onNodeWithText("Tank", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("58°", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Heating · 43 %", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Inlet 17.9 °C", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("card-wh").assertContentDescriptionEquals("Water heater, 58°, Heating · 43 %, Inlet 17.9 °C, Garage")
    }

    @Test
    fun `idle card without inlet has no inlet line`() {
        show(card(StateValue.Climate("off", null, 58.0), mapOf("power" to 0L)))
        compose.onNodeWithText("Idle", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("Inlet", substring = true, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("card-wh").assertContentDescriptionEquals("Water heater, 58°, Idle, Garage")
    }

    @Test
    fun `jeedom card keeps its force switch and shows the mode chip`() {
        show(card(StateValue.Climate("eco", 60.0, 52.0), modes = listOf("off", "eco", "performance")))
        compose.onNodeWithText("Eco", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("52°", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Force Water heater", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `heat mode without power reads Heating`() {
        show(card(StateValue.Climate("heat", null, 58.0)))
        compose.onNodeWithText("Heating", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `unknown temperature shows the none text and no chip`() {
        show(card(StateValue.Climate(null, null, null)))
        compose.onNodeWithText("—", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("card-wh").assertContentDescriptionEquals("Water heater, —, Garage")
    }
}
