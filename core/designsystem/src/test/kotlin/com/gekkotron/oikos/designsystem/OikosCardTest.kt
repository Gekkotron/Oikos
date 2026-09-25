package com.gekkotron.oikos.designsystem

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.components.OikosCard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class OikosCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<String>()

    private fun show(enabled: Boolean = true, onClick: (() -> Unit)? = { events += "click" }, label: String? = null) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) {
            OikosCard(
                modifier = Modifier.testTag("card"),
                enabled = enabled,
                onClick = onClick,
                onLongClick = { events += "long" },
                onLongClickLabel = label,
            ) { Text("Lamp") }
        }
    }

    @Test
    fun `click and long click are reported`() {
        show()
        compose.onNodeWithText("Lamp").performClick()
        compose.onNodeWithText("Lamp").performTouchInput { longClick() }
        assertEquals(listOf("click", "long"), events)
    }

    @Test
    fun `disabled card ignores clicks but still reports a long click`() {
        show(enabled = false)
        compose.onNodeWithTag("card").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithText("Lamp").performClick()
        compose.onNodeWithText("Lamp").performTouchInput { longClick() }
        assertEquals(listOf("long"), events)
    }

    @Test
    fun `long-press-only card exposes a labelled long-click action`() {
        show(onClick = null, label = "Customize")
        val node = compose.onNodeWithTag("card").fetchSemanticsNode()
        assertEquals("Customize", node.config[SemanticsActions.OnLongClick].label)
        compose.onNodeWithTag("card").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithText("Lamp").performTouchInput { longClick() }
        assertEquals(listOf("long"), events)
    }

    @Test
    fun `clickable card carries the long-click label too`() {
        show(label = "Controls")
        assertEquals("Controls", compose.onNodeWithTag("card").fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
    }

    @Test
    fun `long-press-only card is keyboard-focusable`() {
        show(onClick = null, label = "Customize")
        compose.onNodeWithTag("card").requestFocus().assertIsFocused()
    }

    @Test
    fun `Enter, DPad-center and Numpad-enter activate the long-press-only card when focused`() {
        show(onClick = null, label = "Customize")
        compose.onNodeWithTag("card").requestFocus()
        compose.onNodeWithTag("card").performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithTag("card").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("card").performKeyInput { pressKey(Key.NumPadEnter) }
        assertEquals(listOf("long", "long", "long"), events)
    }

    @Test
    fun `disabled card without long click is not enabled`() {
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                OikosCard(modifier = Modifier.testTag("card"), enabled = false, onClick = { events += "click" }) { Text("Lamp") }
            }
        }
        compose.onNodeWithTag("card").assertIsNotEnabled().performClick()
        assertEquals(emptyList(), events)
    }
}
