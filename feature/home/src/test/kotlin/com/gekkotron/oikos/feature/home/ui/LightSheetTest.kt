package com.gekkotron.oikos.feature.home.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.up
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
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
import kotlin.test.assertTrue

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
        testEntity("lamp", Kind.LIGHT, "Lamp", capabilities = Capabilities(brightness = true, colorModes = setOf("color_temp"))),
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
        assertEquals(listOf<Pair<String, Command>>("strip" to Command.TurnOn(effect = "rainbow")), sends.toList())
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

    @Test
    fun `tapping the wheel sends a color`() {
        show()
        compose.onNodeWithTag("card-strip").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Color").performScrollTo().performTouchInput { click(Offset(width / 2f, 1f)) }
        val (id, command) = sends.last()
        assertEquals("strip", id)
        val color = (command as Command.TurnOn).color!!
        assertTrue(color.hue < 2.0 || color.hue > 358.0, "hue ${color.hue}")
        assertTrue(color.saturation > 0.95, "saturation ${color.saturation}")
    }

    @Test
    fun `no wheel without a color mode`() {
        show()
        compose.onNodeWithTag("card-lamp").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Color").assertDoesNotExist()
    }

    @Test
    fun `the white chip sends saturation 0 and is not selected on a coloured strip`() {
        show()
        compose.onNodeWithTag("card-strip").performTouchInput { longClick() }
        compose.onNodeWithText("White").performScrollTo().assertIsNotSelected().performClick()
        val color = (sends.last().second as Command.TurnOn).color!!
        assertEquals(0.0, color.saturation, "saturation ${color.saturation}")
    }

    @Test
    fun `the white chip is selected when the current colour is near white`() {
        val whiteStates = states + ("strip" to EntityState("strip", StateValue.Light(true, 120, "fire_1d", Hs(0.0, 0.0))))
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                HomeScreen(
                    state = HomeUiBuilder.build(entities, whiteStates, emptyList(), RoomFilter.All, LinkState.Connected(TransportMode.DEMO), Locale.US),
                    onSelect = {}, onTap = {}, onSend = { id, c -> sends += id to c },
                    now = LocalTime.NOON,
                )
            }
        }
        compose.onNodeWithTag("card-strip").performTouchInput { longClick() }
        compose.onNodeWithText("White").performScrollTo().assertIsSelected()
    }

    @Test
    fun `no white chip without a color mode`() {
        show()
        compose.onNodeWithTag("card-lamp").performTouchInput { longClick() }
        compose.onNodeWithText("White").assertDoesNotExist()
    }

    @Test
    fun `a swipe that starts away from the marker scrolls the sheet instead of picking a color`() {
        show()
        compose.onNodeWithTag("card-strip").performTouchInput { longClick() }
        // The marker sits lower-left (hue 208°); start at the bottom centre and swipe up across the wheel.
        compose.onNodeWithContentDescription("Color").performScrollTo().performTouchInput {
            swipe(Offset(width / 2f, height - 2f), Offset(width / 2f, 2f), durationMillis = 300)
        }
        assertTrue(sends.none { (it.second as? Command.TurnOn)?.color != null }, "$sends")
    }

    @Test
    fun `dragging the marker picks a color on release`() {
        show()
        compose.onNodeWithTag("card-strip").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Color").performScrollTo().performTouchInput {
            val (mx, my) = WheelMath.pointOf(Hs(208.0, 0.78), width.toFloat())
            swipe(Offset(mx, my), Offset(width / 2f, 2f), durationMillis = 300)
        }
        val color = sends.mapNotNull { (it.second as? Command.TurnOn)?.color }.single()
        assertTrue(color.hue < 5.0 || color.hue > 355.0, "hue ${color.hue}")
    }

    @Test
    fun `a colour echo arriving mid-drag does not replace the dragged colour`() {
        val strip = mutableStateOf(states.getValue("strip"))
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                HomeScreen(
                    state = HomeUiBuilder.build(entities, states + ("strip" to strip.value), emptyList(), RoomFilter.All, LinkState.Connected(TransportMode.DEMO), Locale.US),
                    onSelect = {}, onTap = {}, onSend = { id, c -> sends += id to c },
                    now = LocalTime.NOON,
                )
            }
        }
        compose.onNodeWithTag("card-strip").performTouchInput { longClick() }
        val wheel = compose.onNodeWithContentDescription("Color").performScrollTo()
        wheel.performTouchInput {
            val (mx, my) = WheelMath.pointOf(Hs(208.0, 0.78), width.toFloat())
            down(Offset(mx, my))
            moveTo(Offset(mx, my - 40f))
            moveTo(Offset(width / 2f, 2f))
        }
        strip.value = EntityState("strip", StateValue.Light(true, 120, "fire_1d", Hs(120.0, 0.5)))
        compose.waitForIdle()
        wheel.performTouchInput { up() }
        val color = sends.mapNotNull { (it.second as? Command.TurnOn)?.color }.single()
        assertTrue(color.hue < 5.0 || color.hue > 355.0, "sent ${color.hue}, expected the dragged red")
    }
}
