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
    private val created = mutableListOf<String>()

    private fun card(entity: Entity): CardUi =
        HomeUiBuilder.build(mapOf(entity.uniqueId to entity), emptyMap(), rooms, if (entity.ui.hidden) RoomFilter.Hidden else RoomFilter.All, LinkState.Connecting, Locale.US).card(entity.uniqueId)!!

    private fun show(card: CardUi, fontScale: Float = 1f) = compose.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                CustomizeSheet(card, rooms, onSave = { saved += it }, onCreateRoom = { created += it; "office" }, onDismiss = {})
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
    fun `a new room is created and selected`() {
        show(card(testEntity("lamp", Kind.LIGHT)))
        compose.onNode(hasSetTextAction() and hasText("New room")).performScrollTo().performTextInput("Office")
        compose.onNodeWithText("Add").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(listOf("Office"), created)
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
}
