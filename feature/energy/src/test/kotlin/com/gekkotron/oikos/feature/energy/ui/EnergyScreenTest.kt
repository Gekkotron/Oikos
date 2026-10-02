package com.gekkotron.oikos.feature.energy.ui

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.energy.energyEntity
import com.gekkotron.oikos.feature.energy.numeric
import com.gekkotron.oikos.feature.energy.presentation.EnergyFlowBuilder
import com.gekkotron.oikos.feature.energy.presentation.EnergyNode
import com.gekkotron.oikos.feature.energy.presentation.EnergyUiState
import com.gekkotron.oikos.feature.energy.presentation.NodeKind
import com.gekkotron.oikos.feature.energy.presentation.SensorEdit
import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.transport.LinkState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class EnergyScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun state(entities: List<Entity>, states: List<EntityState>): EnergyUiState =
        EnergyFlowBuilder.build(entities.associateBy { it.uniqueId }, states.associateBy { it.uniqueId }, LinkState.Connecting, 0)

    private val house = listOf(
        energyEntity("grid", EnergyRole.GRID),
        energyEntity("solar", EnergyRole.SOLAR),
        energyEntity("ac", EnergyRole.CONSUMER, name = "AC"),
        energyEntity("heater", EnergyRole.CONSUMER, name = "Water heater"),
    )
    private val houseStates = listOf(numeric("grid", -400.0), numeric("solar", 3200.0), numeric("ac", 600.0), numeric("heater", 1700.0))

    private fun show(state: EnergyUiState, onOpenHome: () -> Unit = {}) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) { EnergyScreen(state, onOpenHome, locale = Locale.US) }
    }

    @Test
    fun `empty state explains and goes home`() {
        var home = 0
        show(EnergyUiState(loading = false, empty = true), onOpenHome = { home++ })
        compose.onNodeWithText("Long-press a power sensor on Home and choose an energy role.").assertExists()
        compose.onNodeWithText("Go to Home").performClick()
        assertEquals(1, home)
    }

    @Test
    fun `subtitle shows self consumption`() {
        show(state(house, houseStates))
        compose.onNodeWithText("Live · self-consumption 87 %").assertExists()
    }

    @Test
    fun `diagram reads as one sentence`() {
        show(state(house, houseStates))
        compose.onNodeWithTag("energy-diagram").assert(
            SemanticsMatcher("sentence") { node ->
                val text = node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.joinToString(" ")
                listOf(
                    "Solar 3.2 kilowatts to the house.",
                    "House uses 2.8 kilowatts.",
                    "Exporting 400 watts to the grid.",
                    "Water heater 1.7 kilowatts.",
                    "AC 600 watts.",
                ).all(text::contains)
            },
        )
    }

    @Test
    fun `four consumers show more and open the sheet`() {
        val four = house + energyEntity("ev", EnergyRole.CONSUMER, name = "EV") + energyEntity("pump", EnergyRole.CONSUMER, name = "Pump")
        show(state(four, houseStates + numeric("ev", 3000.0) + numeric("pump", 50.0)))
        compose.onNodeWithContentDescription("Show all 4 consumers").performClick()
        compose.onNodeWithText("All consumers").assertExists()
        compose.onNodeWithTag("consumers-sheet-consumer:pump").assertExists()
    }

    @Test
    fun `cards list every node`() {
        show(state(house, houseStates))
        for (id in listOf("solar", "grid", "house", "consumer:heater", "consumer:ac")) {
            compose.onNodeWithTag("energy-card-$id").performScrollTo().assertExists()
        }
    }

    @Test
    fun `sign hint shows`() {
        val s = state(listOf(energyEntity("grid", EnergyRole.GRID), energyEntity("solar", EnergyRole.SOLAR)), listOf(numeric("grid", -3000.0), numeric("solar", 1000.0)))
        show(s)
        compose.onNodeWithText("Check the grid or battery sign").assertExists()
        assertTrue(s.hint != null)
    }

    @Test
    fun `consumer bubbles show the consumer name`() {
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                NodeBubble(EnergyNode("consumer:heater", NodeKind.CONSUMER, name = "Water heater", value = 1700.0), Locale.US, androidx.compose.ui.Modifier)
            }
        }
        compose.onNodeWithText("Water heater").assertExists()
    }

    private val saves = mutableListOf<Pair<String, SensorEdit>>()

    private fun showDetails(state: EnergyUiState) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) { EnergyScreen(state, onOpenHome = {}, locale = Locale.US, onSave = { id, edit -> saves += id to edit }) }
    }

    @Test
    fun `tapping a consumer card opens its details with the editor`() {
        showDetails(state(house, houseStates))
        compose.onNodeWithTag("energy-card-consumer:heater").performScrollTo().performClick()
        compose.onNodeWithText("Sensors").assertExists()
        compose.onNode(hasSetTextAction() and hasText("Name")).performTextReplacement("Boiler")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(listOf("heater" to SensorEdit("Boiler", EnergyRole.CONSUMER, invert = false)), saves)
    }

    @Test
    fun `solar details list both inverters`() {
        val s = state(
            listOf(energyEntity("s1", EnergyRole.SOLAR, name = "Inverter A"), energyEntity("s2", EnergyRole.SOLAR, name = "Inverter B")),
            listOf(numeric("s1", 1000.0), numeric("s2", 500.0)),
        )
        showDetails(s)
        compose.onNodeWithTag("energy-card-solar").performScrollTo().performClick()
        compose.onNodeWithText("Inverter A").assertExists()
        compose.onNodeWithText("Inverter B").assertExists()
    }

    @Test
    fun `a derived house says how it is calculated`() {
        showDetails(state(house, houseStates))
        compose.onNodeWithTag("energy-card-house").performScrollTo().performClick()
        compose.onNodeWithText("Calculated: Solar + Grid").assertExists()
    }

    @Test
    fun `a row in the consumers list opens its details`() {
        val four = house + energyEntity("ev", EnergyRole.CONSUMER, name = "EV") + energyEntity("pump", EnergyRole.CONSUMER, name = "Pump")
        showDetails(state(four, houseStates + numeric("ev", 3000.0) + numeric("pump", 50.0)))
        compose.onNodeWithContentDescription("Show all 4 consumers").performClick()
        compose.onNodeWithTag("consumers-sheet-consumer:pump").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Sensors").assertExists()
        compose.onNode(hasSetTextAction() and hasText("Name")).assertExists()
    }

    @Test
    fun `role bubbles show their role name`() {
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                androidx.compose.foundation.layout.Column {
                    NodeBubble(EnergyNode("solar", NodeKind.SOLAR, value = 3200.0), Locale.US, androidx.compose.ui.Modifier)
                    NodeBubble(EnergyNode("grid", NodeKind.GRID, value = 400.0), Locale.US, androidx.compose.ui.Modifier)
                }
            }
        }
        compose.onNodeWithText("Solar").assertExists()
        compose.onNodeWithText("Grid").assertExists()
    }
}
