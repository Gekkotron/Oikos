package com.gekkotron.oikos.feature.home.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.home.presentation.CardUi
import com.gekkotron.oikos.feature.home.presentation.HomeUiBuilder
import com.gekkotron.oikos.feature.home.presentation.RoomFilter
import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.Placement
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.model.UiOverride
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
class CustomizeSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val rooms = listOf(Area("living", "Living room"), Area("kitchen", "Kitchen"))
    private val saved = mutableListOf<UiOverride>()
    private val savedRooms = mutableListOf<List<Area>>()

    // A device Energy or Security already shows is only on Home through search.
    private fun card(entity: Entity): CardUi =
        HomeUiBuilder.build(
            mapOf(entity.uniqueId to entity), emptyMap(), rooms, if (entity.ui.hidden) RoomFilter.Hidden else RoomFilter.All, LinkState.Connecting, Locale.US,
            query = if (entity.ui.hidden || Placement.onHome(entity.kind, entity.deviceClass, entity.ui)) "" else entity.name,
        ).card(entity.uniqueId)!!

    private fun show(card: CardUi, fontScale: Float = 1f) = compose.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                CustomizeSheet(card, rooms, onSave = { r, o -> savedRooms += r; saved += o }, onDismiss = {})
            }
        }
    }

    @Test
    fun `rename, move and favourite are saved together`() {
        show(card(testEntity("lamp", Kind.LIGHT, "Lamp", area = "living")))
        compose.onNode(hasSetTextAction() and hasText("Name")).performTextReplacement("Ceiling")
        compose.onNodeWithText("Kitchen").performScrollTo().performClick()
        compose.onNodeWithText("Favourite").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(listOf(UiOverride(name = "Ceiling", area = "kitchen", favorite = true)), saved)
    }

    @Test
    fun `no room writes Unassigned`() {
        show(card(testEntity("lamp", Kind.LIGHT, area = "living")))
        compose.onNodeWithText("No room").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(UNASSIGNED_AREA, saved.single().area)
    }

    @Test
    fun `a new room is selectable in the picker but not published until Save`() {
        show(card(testEntity("lamp", Kind.LIGHT)))
        compose.onNode(hasSetTextAction() and hasText("New room")).performScrollTo().performTextInput("Office")
        compose.onNodeWithText("Add").performScrollTo().performClick()
        compose.onNodeWithText("Office").assertExists()
        assertTrue(saved.isEmpty())
        assertTrue(savedRooms.isEmpty())
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(listOf(Area("office", "Office")), savedRooms.single())
        assertEquals("office", saved.single().area)
    }

    @Test
    fun `an invalid position blocks saving and says why`() {
        show(card(testEntity("lamp", Kind.LIGHT)))
        compose.onNode(hasSetTextAction() and hasText("Position")).performScrollTo().performTextInput("-3")
        compose.onNodeWithText("Enter a whole number from 0 to 9999.").assertExists()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `sensors offer card style and energy role, switches do not`() {
        show(card(testEntity("power", Kind.SENSOR, deviceClass = "power")))
        // performScrollTo only scrolls a chip's closest scroll parent (its horizontal row), so bring the
        // bottom of the sheet into view first; both chip rows sit just above it.
        compose.onNodeWithText("Reset to defaults").performScrollTo()
        compose.onNodeWithText("Grid").performClick()
        compose.onNodeWithText("Header temperature").performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(UiOverride(energyRole = EnergyRole.GRID, cardStyle = HomeUiBuilder.HERO_WEATHER), saved.single())
    }

    @Test
    fun `switch sheet has no energy role`() {
        show(card(testEntity("sw", Kind.SWITCH)))
        compose.onNodeWithText("Energy role").assertDoesNotExist()
    }

    @Test
    fun `reset saves the default override`() {
        show(card(testEntity("lamp", Kind.LIGHT, ui = UiOverride(name = "X", hidden = true))))
        compose.onNodeWithText("Reset to defaults").performScrollTo().performClick()
        assertEquals(listOf(UiOverride()), saved)
    }

    @Test
    @Config(qualifiers = "w411dp-h500dp")
    fun `every field stays reachable at 2x font scale`() {
        show(card(testEntity("power", Kind.SENSOR, deviceClass = "power")), fontScale = 2f)
        compose.onNodeWithText("Reset to defaults").performScrollTo().assertExists()
    }

    @Test
    fun `invert switch shows for grid and battery only and is saved`() {
        show(card(testEntity("power", Kind.SENSOR, deviceClass = "power", unit = "W")))
        compose.onNodeWithText("Reset to defaults").performScrollTo()
        compose.onNodeWithText("Solar").performScrollTo().performClick()
        compose.onNodeWithText("Invert sign").assertDoesNotExist()
        compose.onNodeWithText("Battery").performScrollTo().performClick()
        compose.onNodeWithText("Invert sign").assertExists()
        compose.onNodeWithText("Grid").performScrollTo().performClick()
        compose.onNodeWithText("Invert sign").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(UiOverride(energyRole = EnergyRole.GRID, energyInvert = true), saved.single())
    }

    @Test
    fun `power role on a non power unit shows the unit hint`() {
        show(card(testEntity("temp", Kind.SENSOR, deviceClass = "temperature", unit = "°C")))
        compose.onNodeWithText("Reset to defaults").performScrollTo()
        compose.onNodeWithText("Needs a power unit (W, kW)").assertDoesNotExist()
        compose.onNodeWithText("Solar").performScrollTo().performClick()
        compose.onNodeWithText("Needs a power unit (W, kW)").assertExists()
        compose.onNodeWithText("Water").performScrollTo().performClick()
        compose.onNodeWithText("Needs a power unit (W, kW)").assertDoesNotExist()
    }

    @Test
    fun `every role is offered`() {
        show(card(testEntity("power", Kind.SENSOR, deviceClass = "power", unit = "W")))
        compose.onNodeWithText("Reset to defaults").performScrollTo()
        for (label in listOf("Solar", "Grid", "Grid export", "Battery", "House", "Consumer", "Water")) {
            compose.onNodeWithText(label).performScrollTo().assertExists()
        }
    }

    @Test
    fun `security sensors and alarms get the Show on Security switch`() {
        show(card(testEntity("mailbox", Kind.BINARY_SENSOR, "Mailbox", deviceClass = "door")))
        compose.onNodeWithText("Show on Security").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(true, saved.single().securityHidden)
    }

    @Test
    fun `other entities have no Show on Security switch`() {
        show(card(testEntity("lamp", Kind.LIGHT, "Lamp")))
        compose.onNodeWithText("Show on Security").assertDoesNotExist()
    }

    @Test
    fun `a device Security shows offers Show on Home instead of Hide from Home`() {
        show(card(testEntity("mailbox", Kind.BINARY_SENSOR, "Mailbox", deviceClass = "door", ui = UiOverride(hidden = true))))
        compose.onNodeWithText("Hide from Home").assertDoesNotExist()
        compose.onNodeWithText("Show on Home").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(UiOverride(homePinned = true), saved.single())
    }

    @Test
    fun `taking a device off Security brings back Hide from Home`() {
        show(card(testEntity("mailbox", Kind.BINARY_SENSOR, "Mailbox", deviceClass = "door")))
        compose.onNodeWithText("Show on Security").performScrollTo().performClick()
        compose.onNodeWithText("Show on Home").assertDoesNotExist()
        compose.onNodeWithText("Hide from Home").assertExists()
    }

    @Test
    fun `other devices keep Hide from Home`() {
        show(card(testEntity("lamp", Kind.LIGHT, "Lamp")))
        compose.onNodeWithText("Show on Home").assertDoesNotExist()
        compose.onNodeWithText("Hide from Home").assertExists()
    }

    @Test
    fun `a switch can be put on Security, asking by default`() {
        show(card(testEntity("siren", Kind.SWITCH, "Siren")))
        compose.onNodeWithText("Ask before turning on").assertDoesNotExist()
        compose.onNodeWithText("Show on Security").performScrollTo().performClick()
        compose.onNodeWithText("Ask before turning on").performScrollTo().assertExists()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(UiOverride(securityAction = true), saved.single())
    }

    @Test
    fun `an opted-in switch offers Keep on Home and Ask before, and turning Show on Security off hides both`() {
        show(card(testEntity("siren", Kind.SWITCH, "Siren", ui = UiOverride(securityAction = true))))
        compose.onNodeWithText("Show on Home").performScrollTo().assertExists()
        compose.onNodeWithText("Ask before turning on").performScrollTo().performClick()
        compose.onNodeWithText("Show on Security").performScrollTo().performClick()
        compose.onNodeWithText("Ask before turning on").assertDoesNotExist()
        compose.onNodeWithText("Show on Home").assertDoesNotExist()
        compose.onNodeWithText("Hide from Home").performScrollTo().assertExists()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(UiOverride(securityAsk = false), saved.single())
    }

    @Test
    fun `lights offer no Security switch`() {
        show(card(testEntity("lamp", Kind.LIGHT)))
        compose.onNodeWithText("Show on Security").assertDoesNotExist()
    }
}
