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

@RunWith(AndroidJUnit4::class)
class HistoryChartsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `line and bar charts render with their descriptions`() {
        val points = (0 until 150).map { ChartPoint(1_696_000_000L + it * 600L, 21.0 + it % 5, 20.5, 22.0) }
        compose.setContent {
            OikosTheme {
                HistoryLineChart(points, Color.Red, { "%.1f".format(it) }, { "$it" }, "line")
                DailyBarsChart(listOf(ChartBar(1_696_000_000, 3.2), ChartBar(1_696_086_400, 4.1)), Color.Blue, { "$it" }, { "$it" }, "bars")
            }
        }
        compose.onNodeWithContentDescription("line").assertExists()
        compose.onNodeWithContentDescription("bars").assertExists()
    }
}
