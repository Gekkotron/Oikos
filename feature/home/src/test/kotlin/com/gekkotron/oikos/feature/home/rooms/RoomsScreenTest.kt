package com.gekkotron.oikos.feature.home.rooms

import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class RoomsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val assigned = mutableListOf<Pair<String, String>>()
    private var done = false

    private fun state(changed: Boolean = false) = RoomsUiState(
        loading = false,
        rows = listOf(
            RoomRowUi("plug", "Plug", listOf("p"), UNASSIGNED_AREA, null),
            RoomRowUi("relay", "Dual relay", listOf("a", "b"), null, null),
            RoomRowUi("kettle", "Kettle", listOf("k"), "kitchen", "Kitchen"),
        ),
        rooms = listOf(Area("kitchen", "Kitchen")),
        assigned = 2,
        changed = changed,
    )

    private fun show(s: RoomsUiState, fontScale: Float = 1f) = compose.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                RoomsScreen(s, onAssign = { id, area -> assigned += id to area }, onCreateRoom = { null }, onDone = { done = true })
            }
        }
    }

    @Test
    fun `rows show their room, and Skip finishes when nothing changed`() {
        show(state())
        compose.onNodeWithText("2 of 3 devices have a room").assertExists()
        compose.onNodeWithText("No room").assertExists()
        compose.onNodeWithText("Several rooms").assertExists()
        compose.onNodeWithText("Skip").performScrollTo().performClick()
        assertTrue(done)
    }

    @Test
    fun `choosing a room in the picker assigns the row`() {
        show(state())
        compose.onNodeWithText("Plug").performClick()
        compose.onNodeWithText("Room for Plug").assertExists()
        // "Kitchen" is both the kettle row's room and a picker option; the sheet is composed last.
        compose.onAllNodesWithText("Kitchen").onLast().performClick()
        assertEquals(listOf("plug" to "kitchen"), assigned)
        compose.onNodeWithText("Room for Plug").assertDoesNotExist()
    }

    @Test
    fun `after a change the button says Done`() {
        show(state(changed = true))
        compose.onNodeWithText("Done").assertExists()
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h914dp")
    fun `the screen is translated`() {
        show(state())
        compose.onNodeWithText("Dans quelle pièce est chaque appareil ?").assertExists()
        compose.onNodeWithText("Passer").assertExists()
    }

    @Test
    @Config(qualifiers = "w411dp-h500dp")
    fun `the finish button stays reachable at 2x font scale`() {
        show(state(), fontScale = 2f)
        // A lazy item may not be composed yet, so scroll the list to it rather than performScrollTo.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Skip"))
        compose.onNodeWithText("Skip").assertExists()
    }
}
