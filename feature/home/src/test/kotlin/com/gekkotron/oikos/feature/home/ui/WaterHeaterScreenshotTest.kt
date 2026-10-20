package com.gekkotron.oikos.feature.home.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosBackground
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.home.distinctColors
import com.gekkotron.oikos.feature.home.luminanceAt
import com.gekkotron.oikos.feature.home.presentation.CardUi
import com.gekkotron.oikos.feature.home.presentation.HomeUiBuilder
import com.gekkotron.oikos.feature.home.presentation.RoomFilter
import com.gekkotron.oikos.feature.home.renderPng
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import kotlin.test.assertTrue

/**
 * Water heater view spec §9: the WaterHeaterController card heating and idle, the Jeedom card with its Force
 * switch, at home-grid width (two columns), and both sheets, in both themes.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class WaterHeaterScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val controllerCaps = Capabilities(hvacModes = emptyList(), minTemp = 40.0, maxTemp = 65.0, tempStep = 1.0)
    private val jeedomCaps = Capabilities(hvacModes = listOf("off", "eco", "performance"), minTemp = 40.0, maxTemp = 65.0, tempStep = 1.0)

    private fun card(
        caps: Capabilities,
        climate: StateValue.Climate,
        attributes: Map<String, Any?> = emptyMap(),
        numbers: List<String> = emptyList(),
    ): CardUi {
        val wh = testEntity("wh", Kind.WATER_HEATER, "Water heater", area = "garage", unit = "°C", capabilities = caps, deviceId = "dev")
        val companions = numbers.mapIndexed { i, name ->
            testEntity("n$i", Kind.NUMBER, name, area = "garage", unit = "%", deviceId = "dev",
                capabilities = Capabilities(min = 0.0, max = 100.0, step = 1.0))
        }
        val states = mapOf("wh" to EntityState("wh", climate, attributes)) +
            companions.associate { it.uniqueId to EntityState(it.uniqueId, StateValue.Numeric(43.0)) }
        return HomeUiBuilder.build(
            (listOf(wh) + companions).associateBy { it.uniqueId }, states, listOf(Area("garage", "Garage")),
            RoomFilter.All, LinkState.Connecting, Locale.US,
        ).card("wh")!!
    }

    private val heating get() = card(controllerCaps, StateValue.Climate("heat", null, 58.0), mapOf("power" to 43L, "inlet_temperature" to 17.9))
    private val idle get() = card(controllerCaps, StateValue.Climate("off", null, 61.5), mapOf("power" to 0L, "inlet_temperature" to 18.2))
    private val jeedomEco get() = card(jeedomCaps, StateValue.Climate("eco", 60.0, 52.0))
    private val jeedomOff get() = card(jeedomCaps, StateValue.Climate("off", 60.0, 61.0))

    private fun check(name: String, dark: Boolean) {
        val bitmap = compose.renderPng(name)
        assertTrue(bitmap.distinctColors() > 8)
        if (dark) assertTrue(bitmap.luminanceAt(4, 4) < 0.35f) else assertTrue(bitmap.luminanceAt(4, 4) > 0.6f)
    }

    private fun cards(theme: ThemeChoice) = compose.setContent {
        OikosTheme(theme) {
            OikosBackground {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (pair in listOf(heating to idle, jeedomEco to jeedomOff)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            EntityCard(pair.first, onClick = {}, onLongClick = {}, modifier = Modifier.weight(1f))
                            EntityCard(pair.second, onClick = {}, onLongClick = {}, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `cards in Night Glass`() {
        cards(ThemeChoice.NIGHT_GLASS)
        check("water_heater_cards_night", dark = true)
    }

    @Test
    fun `cards in Warm Minimal`() {
        cards(ThemeChoice.WARM_MINIMAL)
        check("water_heater_cards_warm", dark = false)
    }

    private fun sheets(theme: ThemeChoice) {
        val controller = card(controllerCaps, StateValue.Climate("heat", null, 58.0), numbers = listOf("Power 1", "Power 2", "Power 3"))
        val jeedom = card(jeedomCaps, StateValue.Climate("eco", 60.0, 52.0), numbers = listOf("Force power"))
        compose.setContent {
            OikosTheme(theme) {
                OikosBackground {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WaterHeaterControls(controller, onSend = {}, onSendCompanion = { _, _ -> })
                        WaterHeaterControls(jeedom, onSend = {}, onSendCompanion = { _, _ -> })
                    }
                }
            }
        }
    }

    @Test
    fun `sheets in Night Glass`() {
        sheets(ThemeChoice.NIGHT_GLASS)
        check("water_heater_sheets_night", dark = true)
    }

    @Test
    fun `sheets in Warm Minimal`() {
        sheets(ThemeChoice.WARM_MINIMAL)
        check("water_heater_sheets_warm", dark = false)
    }
}
