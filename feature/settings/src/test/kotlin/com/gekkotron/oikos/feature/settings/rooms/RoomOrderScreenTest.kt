package com.gekkotron.oikos.feature.settings.rooms

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.model.Area
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class RoomOrderScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val rooms = listOf(Area("bath", "Bath"), Area("living", "Living"), Area("office", "Office"))
    private val moves = mutableListOf<Pair<String, Int>>()
    private val commits = mutableListOf<List<String>>()
    private var assign = 0

    private fun top(name: String) = compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun `rooms are listed with unassigned last and not draggable`() {
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) { RoomOrderScreen(RoomOrderUi(rooms, hasUnassigned = true), {}, { _, _ -> }, {}, {}) }
        }
        compose.onNodeWithText("Bath").assertExists()
        compose.onNodeWithText("Unassigned").assertExists()
        compose.onNode(hasText("Unassigned") and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).assertDoesNotExist()
        assertTrue(top("Unassigned") > top("Office"))
    }

    @Test
    fun `talkback move actions, none past the ends`() {
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) { RoomOrderScreen(RoomOrderUi(rooms), {}, { id, d -> moves += id to d }, {}, {}) }
        }
        compose.onNodeWithText("Living").performCustomAccessibilityActionWithLabel("Move up")
        compose.onNodeWithText("Living").performCustomAccessibilityActionWithLabel("Move down")
        assertEquals(listOf("living" to -1, "living" to 1), moves)
        compose.onNode(hasText("Bath") and hasAction("Move up")).assertDoesNotExist()
        compose.onNode(hasText("Office") and hasAction("Move down")).assertDoesNotExist()
    }

    @Test
    fun `assign rooms button calls back`() {
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) { RoomOrderScreen(RoomOrderUi(rooms), {}, { _, _ -> }, { assign++ }, {}) }
        }
        compose.onNodeWithText("Assign rooms").performClick()
        assertEquals(1, assign)
    }

    @Test
    fun `a new reset key snaps a dragged list back to the state`() {
        var key by mutableIntStateOf(0)
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                RoomOrderScreen(RoomOrderUi(rooms), { commits += it }, { _, _ -> }, {}, {}, resetKey = key)
            }
        }
        assertTrue(top("Bath") < top("Living"))
        val step = top("Living") - top("Bath")
        compose.onNodeWithTag("drag-handle-bath", useUnmergedTree = true).performTouchInput {
            down(center)
            moveBy(Offset(0f, step / 2))
            moveBy(Offset(0f, step / 2))
            moveBy(Offset(0f, step / 4))
            up()
        }
        compose.waitForIdle()
        assertTrue(top("Bath") > top("Living"), "Bath should now be below Living")
        assertEquals(listOf(listOf("living", "bath", "office")), commits)

        key = 1
        compose.waitForIdle()
        assertTrue(top("Bath") < top("Living"), "Bath should be back above Living")
        assertTrue(top("Living") < top("Office"))
    }

    private fun hasAction(label: String) = SemanticsMatcher("has $label") { node ->
        node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().any { it.label == label }
    }
}
