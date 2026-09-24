package com.gekkotron.oikos.designsystem

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class ThemeTest {
    @get:Rule
    val compose = createComposeRule()

    private fun colorsFor(choice: ThemeChoice): OikosColors {
        lateinit var captured: OikosColors
        compose.setContent { OikosTheme(choice) { captured = OikosTheme.colors } }
        compose.waitForIdle()
        return captured
    }

    @Test
    fun `night glass is dark`() = assertEquals(NightGlassColors, colorsFor(ThemeChoice.NIGHT_GLASS))

    @Test
    fun `warm minimal is light`() = assertEquals(WarmMinimalColors, colorsFor(ThemeChoice.WARM_MINIMAL))

    @Test
    @Config(qualifiers = "night")
    fun `system follows a dark system`() = assertEquals(NightGlassColors, colorsFor(ThemeChoice.SYSTEM))

    @Test
    @Config(qualifiers = "notnight")
    fun `system follows a light system`() = assertEquals(WarmMinimalColors, colorsFor(ThemeChoice.SYSTEM))
}
