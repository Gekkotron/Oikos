package com.gekkotron.oikos.feature.alerts.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.alerts.demo.DemoAlerts
import com.gekkotron.oikos.designsystem.OikosBackground
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.alerts.distinctColors
import com.gekkotron.oikos.feature.alerts.luminanceAt
import com.gekkotron.oikos.feature.alerts.renderPng
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class RecentAlertsScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-10-07T12:00:00Z")

    private fun render(theme: ThemeChoice, name: String, alerts: List<Alert>, off: Boolean) = run {
        compose.setContent {
            OikosTheme(theme) {
                OikosBackground { RecentAlertsSection(alerts, now, off, onTurnOn = {}, onOpen = {}, onSeeAll = {}) }
            }
        }
        compose.renderPng(name)
    }

    @Test
    fun `rows in Night Glass`() {
        val bitmap = render(ThemeChoice.NIGHT_GLASS, "recent-rows-night", DemoAlerts.all(now).take(3), off = false)
        assertTrue(bitmap.distinctColors() > 8)
        assertTrue(bitmap.luminanceAt(4, 4) < 0.35f)
    }

    @Test
    fun `rows with the banner in Warm Minimal`() {
        val bitmap = render(ThemeChoice.WARM_MINIMAL, "recent-banner-warm", DemoAlerts.all(now).take(3), off = true)
        assertTrue(bitmap.distinctColors() > 8)
        assertTrue(bitmap.luminanceAt(4, 4) > 0.6f)
    }

    @Test
    fun `empty in both themes`() {
        assertTrue(render(ThemeChoice.NIGHT_GLASS, "recent-empty-night", emptyList(), off = false).distinctColors() > 3)
    }
}
