package com.gekkotron.oikos.feature.home.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
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
import com.gekkotron.oikos.feature.home.presentation.CardFormatter
import com.gekkotron.oikos.feature.home.presentation.CardUi
import com.gekkotron.oikos.feature.home.presentation.CardValue
import com.gekkotron.oikos.feature.home.presentation.TapAction
import com.gekkotron.oikos.feature.home.renderPng
import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UiOverride
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import kotlin.test.assertTrue

/** Water heater spec: the Eco and Forced cards and the control sheet, in both themes. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class WaterHeaterScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val caps = Capabilities(hvacModes = listOf("off", "eco", "performance"), minTemp = 40.0, maxTemp = 65.0, tempStep = 1.0)

    private fun card(mode: String): CardUi {
        val value = StateValue.Climate(mode, 60.0, 52.0)
        val entity = testEntity("wh", Kind.WATER_HEATER, "Water heater", area = "garage", capabilities = caps)
        return CardUi(
            id = "wh", name = "Water heater", kind = Kind.WATER_HEATER, protocolKind = Kind.WATER_HEATER,
            deviceClass = null, areaName = "Garage",
            value = CardFormatter.format(entity, value, Locale.US),
            state = value, capabilities = caps, unit = null, on = mode == "performance",
            wide = false, available = true, pending = false, tap = TapAction.OPEN_SHEET,
            hasSheet = true, areaId = "garage", ui = UiOverride(), hidden = false,
            forceToggle = mode == "performance",
        )
    }

    private fun check(name: String, dark: Boolean) {
        val bitmap = compose.renderPng(name)
        assertTrue(bitmap.distinctColors() > 8)
        if (dark) assertTrue(bitmap.luminanceAt(4, 4) < 0.35f) else assertTrue(bitmap.luminanceAt(4, 4) > 0.6f)
    }

    private fun cardContent(theme: ThemeChoice, mode: String) = compose.setContent {
        OikosTheme(theme) {
            OikosBackground {
                Column(Modifier.padding(16.dp)) { EntityCard(card = card(mode), onClick = {}, onLongClick = {}, onForce = {}) }
            }
        }
    }

    @Test
    fun `eco card in Night Glass`() {
        cardContent(ThemeChoice.NIGHT_GLASS, "eco")
        check("water_heater_card_eco_night", dark = true)
    }

    @Test
    fun `eco card in Warm Minimal`() {
        cardContent(ThemeChoice.WARM_MINIMAL, "eco")
        check("water_heater_card_eco_warm", dark = false)
    }

    @Test
    fun `forced card in Night Glass`() {
        cardContent(ThemeChoice.NIGHT_GLASS, "performance")
        check("water_heater_card_forced_night", dark = true)
    }

    @Test
    fun `forced card in Warm Minimal`() {
        cardContent(ThemeChoice.WARM_MINIMAL, "performance")
        check("water_heater_card_forced_warm", dark = false)
    }

    private fun sheetContent(theme: ThemeChoice) {
        val whCard = card("eco").copy(
            companions = listOf(
                CardUi(
                    id = "wh_force_power", name = "Force power", kind = Kind.NUMBER, protocolKind = Kind.NUMBER,
                    deviceClass = null, areaName = "Garage",
                    value = CardValue.Measurement("60", "%"), state = StateValue.Numeric(60.0),
                    capabilities = Capabilities(min = 0.0, max = 100.0, step = 10.0),
                    unit = "%", on = false, wide = false, available = true, pending = false,
                    tap = TapAction.OPEN_SHEET, hasSheet = true, areaId = "garage",
                    ui = UiOverride(), hidden = false,
                ),
            ),
        )
        compose.setContent {
            OikosTheme(theme) {
                OikosBackground {
                    Column(Modifier.padding(24.dp)) { WaterHeaterControls(whCard, onSend = {}, onSendCompanion = { _, _ -> }) }
                }
            }
        }
    }

    @Test
    fun `sheet in Night Glass`() {
        sheetContent(ThemeChoice.NIGHT_GLASS)
        check("water_heater_sheet_night", dark = true)
    }

    @Test
    fun `sheet in Warm Minimal`() {
        sheetContent(ThemeChoice.WARM_MINIMAL)
        check("water_heater_sheet_warm", dark = false)
    }
}
