package com.gekkotron.oikos.designsystem

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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

    private fun show(enabled: Boolean = true) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) {
            OikosCard(enabled = enabled, onClick = { events += "click" }, onLongClick = { events += "long" }) { Text("Lamp") }
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
    fun `disabled card ignores clicks`() {
        show(enabled = false)
        compose.onNodeWithText("Lamp").assertIsNotEnabled().performClick()
        assertEquals(emptyList(), events)
    }
}
