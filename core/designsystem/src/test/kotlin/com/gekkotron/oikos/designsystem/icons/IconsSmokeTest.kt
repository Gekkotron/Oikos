package com.gekkotron.oikos.designsystem.icons

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IconsSmokeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `every opening icon draws at 0, half and full, out of range included, at 24 dp`() {
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                Row {
                    ShutterIcon(0.6f, Modifier.testTag("shutter"))
                    for (t in listOf(-0.5f, 0f, 0.5f, 1f, 1.5f)) {
                        ShutterIcon(t)
                        GarageIcon(t, Color.Yellow)
                        DoorIcon(t, Color.Yellow)
                        WindowIcon(t, null)
                    }
                }
            }
        }
        compose.onNodeWithTag("shutter").assertWidthIsEqualTo(24.dp).assertHeightIsEqualTo(24.dp)
    }

    @Test
    fun `every static glyph draws, with and without an accent`() {
        compose.setContent {
            OikosTheme(ThemeChoice.WARM_MINIMAL) {
                Row {
                    OikosGlyph.entries.forEach { glyph ->
                        StaticIcon(glyph)
                        StaticIcon(glyph, Color.Yellow)
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
