package com.gekkotron.oikos.feature.home.ui

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import com.gekkotron.oikos.model.UiOverride
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.home.presentation.HomeUiBuilder
import com.gekkotron.oikos.feature.home.presentation.HomeUiState
import com.gekkotron.oikos.feature.home.presentation.RoomFilter
import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.EntityState
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
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val entities = listOf(
        testEntity("lamp", Kind.LIGHT, "Floor lamp", area = "living", capabilities = Capabilities(brightness = true)),
        testEntity("shutter", Kind.COVER, "Shutter", area = "living", capabilities = Capabilities(position = true)),
        testEntity(
            "ac", Kind.CLIMATE, "Air conditioner", area = "living",
            capabilities = Capabilities(hvacModes = listOf("off", "heat"), minTemp = 16.0, maxTemp = 30.0, tempStep = 0.5),
        ),
        testEntity("coffee", Kind.SWITCH, "Coffee machine", area = "kitchen"),
        testEntity("plug", Kind.SWITCH, "Garage plug", area = "garage"),
        testEntity("temp", Kind.SENSOR, "Temperature", area = "living", unit = "°C"),
    ).associateBy { it.uniqueId }
    private val states = mapOf(
        "lamp" to EntityState("lamp", StateValue.Light(true, 180)),
        "shutter" to EntityState("shutter", StateValue.Cover(CoverState.OPEN, 60)),
        "ac" to EntityState("ac", StateValue.Climate("heat", 21.0, 22.4)),
        "coffee" to EntityState("coffee", StateValue.OnOff(false)),
        "plug" to EntityState("plug", StateValue.OnOff(false), available = false),
    )
    private val areas = listOf(Area("living", "Living room"), Area("kitchen", "Kitchen"), Area("garage", "Garage"))

    private val taps = mutableListOf<String>()
    private val sends = mutableListOf<Pair<String, Command>>()
    private val selects = mutableListOf<RoomFilter>()
    private val customized = mutableListOf<Pair<String, UiOverride>>()

    private fun state(): HomeUiState =
        HomeUiBuilder.build(entities, states, areas, RoomFilter.All, LinkState.Connected(TransportMode.DEMO), Locale.US)

    private fun show(insets: WindowInsets? = null, fontScale: () -> Float = { 1f }) = compose.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale())) {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                HomeScreen(
                    state = state(),
                    onSelect = { selects += it },
                    onTap = { taps += it.id },
                    onSend = { id, c -> sends += id to c },
                    onCustomize = { id, _, o -> customized += id to o },
                    now = LocalTime.NOON,
                    windowInsets = insets ?: ScaffoldDefaults.contentWindowInsets,
                )
            }
        }
    }

    @Test
    fun `tapping a light card calls onTap`() {
        show()
        compose.onNodeWithTag("card-lamp").performClick()
        assertEquals(listOf("lamp"), taps)
    }

    @Test
    fun `card describes name, state and room`() {
        show()
        compose.onNodeWithContentDescription("Floor lamp, 71%, Living room").assertExists()
        compose.onNodeWithContentDescription("Shutter, 60% open, Living room").assertExists()
    }

    @Test
    fun `header shows greeting and demo pill`() {
        show()
        compose.onNodeWithText("Good afternoon").assertExists()
        compose.onNodeWithText("Demo").assertExists()
    }

    @Test
    fun `tapping a cover opens its control sheet and Close sends Command_Close`() {
        show()
        compose.onNodeWithTag("card-shutter").performClick()
        compose.onNodeWithText("Close").performClick()
        assertEquals(listOf<Pair<String, Command>>("shutter" to Command.Close), sends)
        assertEquals(emptyList(), taps)
    }

    @Test
    fun `climate sheet increases the setpoint by one step`() {
        show()
        compose.onNodeWithTag("card-ac").performClick()
        compose.onNodeWithContentDescription("Increase").performClick()
        assertEquals(listOf<Pair<String, Command>>("ac" to Command.SetTemperature(21.5)), sends)
    }

    @Test
    fun `unavailable card ignores taps and can still be customized`() {
        show()
        compose.onNodeWithTag("card-plug").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick)).performClick()
        compose.onNodeWithContentDescription("Garage plug, Unavailable, Garage").assertExists()
        assertEquals(emptyList(), taps)
        compose.onNodeWithTag("card-plug").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithText("Hide from Home").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(listOf("plug" to UiOverride(hidden = true)), customized)
    }

    @Test
    fun `long-press on a sensor opens the override sheet and saves a rename`() {
        show()
        compose.onNodeWithTag("card-temp").performScrollTo().performTouchInput { longClick() }
        compose.onNode(hasSetTextAction() and hasText("Name")).performTextReplacement("Living temperature")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(listOf("temp" to UiOverride(name = "Living temperature")), customized)
    }

    @Test
    fun `long-press on a dimmable light opens its controls, and Customize opens the override sheet`() {
        show()
        compose.onNodeWithTag("card-lamp").performTouchInput { longClick() }
        compose.onNodeWithText("Brightness").assertExists()
        compose.onNodeWithText("Customize…").performScrollTo().performClick()
        compose.onNodeWithText("Hide from Home").assertExists()
        compose.onNodeWithText("Brightness").assertDoesNotExist()
    }

    @Test
    fun `long-press actions are labelled for accessibility`() {
        show()
        assertEquals("Controls", compose.onNodeWithTag("card-lamp").fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
        compose.onNodeWithTag("card-temp").performScrollTo()
        assertEquals("Customize", compose.onNodeWithTag("card-temp").fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
    }

    @Test
    fun `room chip selects its area`() {
        show()
        compose.onNodeWithText("Kitchen").performClick()
        assertEquals(listOf<RoomFilter>(RoomFilter.Area("kitchen")), selects)
    }

    @Test
    @Config(qualifiers = "w411dp-h500dp")
    fun `control sheet scrolls to its last controls at 2x font scale`() {
        show { 2f }
        compose.onNodeWithTag("card-ac").performClick()
        compose.onNodeWithText("Heat").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf<Pair<String, Command>>("ac" to Command.SetHvacMode("heat")), sends)
    }

    @Test
    fun `status bar gets a scrim and side insets pad the grid`() {
        show(insets = WindowInsets(left = 40.dp, top = 30.dp))
        val scrim = compose.onNodeWithTag("status-scrim").getBoundsInRoot()
        assertEquals(30.dp, scrim.bottom - scrim.top)
        assertTrue(compose.onNodeWithTag("card-ac").getBoundsInRoot().left >= 56.dp)
    }

    @Test
    fun `cards grow with font scale`() {
        var scale by mutableFloatStateOf(1f)
        show { scale }
        val normal = compose.onNodeWithTag("card-coffee").getBoundsInRoot().let { it.bottom - it.top }
        scale = 2f
        compose.waitForIdle()
        val large = compose.onNodeWithTag("card-coffee").getBoundsInRoot().let { it.bottom - it.top }
        assertTrue(large > normal, "card height $normal at 1x vs $large at 2x")
    }

    @Test
    fun `hidden chip lists hidden cards, described as hidden`() {
        val withHidden = entities + ("secret" to testEntity("secret", Kind.SWITCH, "Secret switch", area = "kitchen", ui = UiOverride(hidden = true)))
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                HomeScreen(
                    state = HomeUiBuilder.build(withHidden, states, areas, RoomFilter.Hidden, LinkState.Connected(TransportMode.DEMO), Locale.US),
                    onSelect = { selects += it }, onTap = {}, onSend = { _, _ -> }, now = LocalTime.NOON,
                )
            }
        }
        compose.onNodeWithText("Hidden (1)").assertExists()
        // No state was given for "secret", so its value is "—"; only the name and the hidden suffix matter here.
        compose.onNodeWithContentDescription("Secret switch, —, Kitchen, hidden").assertExists()
    }

    @Test
    fun `long-pressing the header temperature opens its override sheet, so the hero style can be undone`() {
        val withHero = entities + ("outdoor" to testEntity("outdoor", Kind.SENSOR, "Outdoor", unit = "°C", ui = UiOverride(cardStyle = HomeUiBuilder.HERO_WEATHER)))
        val customizedHere = mutableListOf<Pair<String, UiOverride>>()
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                HomeScreen(
                    state = HomeUiBuilder.build(withHero, states + ("outdoor" to EntityState("outdoor", StateValue.Numeric(12.0))), areas, RoomFilter.All, LinkState.Connected(TransportMode.DEMO), Locale.US),
                    onSelect = {}, onTap = {}, onSend = { _, _ -> }, now = LocalTime.NOON,
                    onCustomize = { id, _, o -> customizedHere += id to o },
                )
            }
        }
        compose.onNodeWithTag("hero").performTouchInput { longClick() }
        compose.onNodeWithText("Standard").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(listOf("outdoor" to UiOverride()), customizedHere)
    }

    @Test
    fun `the header temperature has no click action, only a labelled long-click`() {
        val withHero = entities + ("outdoor" to testEntity("outdoor", Kind.SENSOR, "Outdoor", unit = "°C", ui = UiOverride(cardStyle = HomeUiBuilder.HERO_WEATHER)))
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                HomeScreen(
                    state = HomeUiBuilder.build(withHero, states + ("outdoor" to EntityState("outdoor", StateValue.Numeric(12.0))), areas, RoomFilter.All, LinkState.Connected(TransportMode.DEMO), Locale.US),
                    onSelect = {}, onTap = {}, onSend = { _, _ -> }, now = LocalTime.NOON,
                )
            }
        }
        compose.onNodeWithTag("hero").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        assertEquals("Customize", compose.onNodeWithTag("hero").fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
    }
}
