package com.gekkotron.oikos.feature.home.ui

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.home.presentation.HomeUiBuilder
import com.gekkotron.oikos.feature.home.presentation.RoomFilter
import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Hs
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalTime
import java.util.Locale
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class LightSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val sends = mutableListOf<Pair<String, Command>>()

    private val entities = listOf(
        testEntity(
            "strip", Kind.LIGHT, "Strip", deviceId = "zl",
            capabilities = Capabilities(brightness = true, colorModes = setOf("xy"), effects = listOf("solid", "fire_1d", "rainbow")),
        ),
        testEntity("speed", Kind.NUMBER, "Effect speed", deviceId = "zl", partOf = "strip", capabilities = Capabilities(min = 0.0, max = 255.0, step = 1.0)),
        testEntity("lamp", Kind.LIGHT, "Lamp", capabilities = Capabilities(brightness = true)),
    ).associateBy { it.uniqueId }
    private val states = mapOf(
        "strip" to EntityState("strip", StateValue.Light(true, 120, "fire_1d", Hs(208.0, 0.78))),
        "speed" to EntityState("speed", StateValue.Numeric(38.0)),
        "lamp" to EntityState("lamp", StateValue.Light(true, 200)),
    )

    private fun show() = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) {
            HomeScreen(
                state = HomeUiBuilder.build(entities, states, emptyList(), RoomFilter.All, LinkState.Connected(TransportMode.DEMO), Locale.US),
                onSelect = {}, onTap = {}, onSend = { id, c -> sends += id to c },
                now = LocalTime.NOON,
            )
        }
    }

    @Test
    fun `the strip's sheet shows its effects with the current one selected, and a tap sends it`() {
        show()
        compose.onNodeWithTag("card-strip").performTouchInput { longClick() }
        compose.onNodeWithText("Effect").assertExists()
        compose.onNodeWithText("Fire 1d").assertIsSelected()
        compose.onNodeWithText("Rainbow").assertIsNotSelected().performClick()
        assertEquals("strip" to Command.TurnOn(effect = "rainbow"), sends.last())
    }

    @Test
    fun `the device's numbers are in the sheet, not on the grid, and send to their own id`() {
        show()
        compose.onNodeWithTag("card-speed").assertDoesNotExist()
        compose.onNodeWithTag("card-strip").performTouchInput { longClick() }
        compose.onNodeWithText("Effect speed").performScrollTo().assertExists()
        compose.onNodeWithText("38").assertExists()
    }

    @Test
    fun `a dimmable light without effects shows no effect section`() {
        show()
        compose.onNodeWithTag("card-lamp").performTouchInput { longClick() }
        compose.onNodeWithText("Brightness").assertExists()
        compose.onNodeWithText("Effect").assertDoesNotExist()
    }
}
