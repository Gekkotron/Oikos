package com.gekkotron.oikos.designsystem.charts

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class HistoryChartsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `line and bar charts render with their descriptions`() {
        val points = (0 until 150).map { ChartPoint(1_696_000_000L + it * 600L, 21.0 + it % 5, 20.5, 22.0) }
        compose.setContent {
            OikosTheme {
                HistoryLineChart(points, Color.Red, { "%.1f".format(it) }, { "$it" }, "line")
                DailyBarsChart(listOf(ChartBar(1_696_000_000, 3.2), ChartBar(1_696_086_400, 4.1)), 3.65, Color.Blue, { "$it" }, { "$it" }, "bars")
            }
        }
        compose.onNodeWithContentDescription("line").assertExists()
        compose.onNodeWithContentDescription("bars").assertExists()
    }

    @Test
    fun `zooming in stops at a tenth of the range or four samples, whichever is wider`() {
        assertEquals(8_640.0, maxZoomSpan(span = 86_400, step = 300)) // 24 h of 5 min samples: 2.4 h on screen
        assertEquals(4 * 86_400.0, maxZoomSpan(span = 29 * 86_400, step = 86_400)) // 30 daily bars: 4 days
        assertEquals(4 * 86_400.0, maxZoomSpan(span = 6 * 86_400, step = 86_400)) // 7 daily bars: 4 days
    }
}
