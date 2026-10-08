package com.gekkotron.oikos.feature.history.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosBackground
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.history.distinctColors
import com.gekkotron.oikos.feature.history.luminanceAt
import com.gekkotron.oikos.feature.history.presentation.ChartContent
import com.gekkotron.oikos.feature.history.presentation.ChartMode
import com.gekkotron.oikos.feature.history.presentation.HistoryUi
import com.gekkotron.oikos.feature.history.renderPng
import com.gekkotron.oikos.history.DailyTotals
import com.gekkotron.oikos.history.DayTotal
import com.gekkotron.oikos.history.HistoryPoint
import com.gekkotron.oikos.history.HistoryRange
import com.gekkotron.oikos.history.Series
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.sin
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class HistoryScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val start = 1_790_000_000L

    private fun line() = ChartContent.Line(
        Series(600, (0 until 150).map {
            val mean = 400.0 + 250.0 * sin(it / 12.0)
            HistoryPoint(start + it * 600L, mean, mean - 60, mean + 80)
        }),
        min = 90.0, avg = 400.0, max = 730.0,
    )

    private fun bars() = ChartContent.Bars(
        DailyTotals((0 until 7).map { DayTotal(start + it * 86_400L, 6.0 + (it * 7 % 5)) }),
        total = 52.0,
    )

    private fun render(theme: ThemeChoice, name: String, state: HistoryUi) = run {
        compose.setContent {
            OikosTheme(theme) {
                OikosBackground {
                    HistoryScreen(
                        state, onBack = {}, onRange = {}, onMode = {}, onRetry = {},
                        zone = ZoneOffset.UTC, locale = Locale.UK,
                    )
                }
            }
        }
        compose.renderPng(name)
    }

    @Test
    fun `a line in Night Glass`() {
        val state = HistoryUi(title = "Boiler", room = "Garage", live = 420.0, unit = "W", energy = true, toggle = true, content = line())
        val bitmap = render(ThemeChoice.NIGHT_GLASS, "history-line-night", state)
        assertTrue(bitmap.distinctColors() > 8)
        assertTrue(bitmap.luminanceAt(4, 4) < 0.35f)
    }

    @Test
    fun `a line in Warm Minimal`() {
        val state = HistoryUi(title = "Boiler", room = "Garage", live = 420.0, unit = "W", range = HistoryRange.D7, content = line())
        val bitmap = render(ThemeChoice.WARM_MINIMAL, "history-line-warm", state)
        assertTrue(bitmap.distinctColors() > 8)
        assertTrue(bitmap.luminanceAt(4, 4) > 0.6f)
    }

    @Test
    fun `daily bars in Night Glass`() {
        val state = HistoryUi(live = 1800.0, unit = "W", range = HistoryRange.D7, energy = true, toggle = true, mode = ChartMode.DAILY, content = bars())
        val bitmap = render(ThemeChoice.NIGHT_GLASS, "history-bars-night", state)
        assertTrue(bitmap.distinctColors() > 8)
        assertTrue(bitmap.luminanceAt(4, 4) < 0.35f)
    }

    @Test
    fun `not recorded in Warm Minimal`() {
        val state = HistoryUi(title = "Lamp", content = ChartContent.NotRecorded)
        val bitmap = render(ThemeChoice.WARM_MINIMAL, "history-not-recorded-warm", state)
        assertTrue(bitmap.distinctColors() > 4)
        assertTrue(bitmap.luminanceAt(4, 4) > 0.6f)
    }
}
