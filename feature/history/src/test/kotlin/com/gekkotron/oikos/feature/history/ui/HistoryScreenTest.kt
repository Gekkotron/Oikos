package com.gekkotron.oikos.feature.history.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.history.presentation.ChartContent
import com.gekkotron.oikos.feature.history.presentation.ChartMode
import com.gekkotron.oikos.feature.history.presentation.HistoryUi
import com.gekkotron.oikos.history.DailyTotals
import com.gekkotron.oikos.history.DayTotal
import com.gekkotron.oikos.history.HistoryPoint
import com.gekkotron.oikos.history.HistoryRange
import com.gekkotron.oikos.history.Series
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class HistoryScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val ranges = mutableListOf<HistoryRange>()
    private val modes = mutableListOf<ChartMode>()
    private var retries = 0
    private var backs = 0

    private val line = ChartContent.Line(
        Series(3600, (0 until 24).map { HistoryPoint(1_760_000_000L + it * 3600, 100.0 + it, 90.0, 130.0) }),
        min = 90.0, avg = 112.0, max = 130.0,
    )

    private fun show(state: HistoryUi) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) {
            HistoryScreen(
                state, onBack = { backs++ }, onRange = { ranges += it }, onMode = { modes += it }, onRetry = { retries++ },
                zone = ZoneOffset.UTC, locale = Locale.UK,
            )
        }
    }

    @Test
    fun `the header and the stats of a line`() {
        show(HistoryUi(title = "Boiler", room = "Garage", live = 120.0, unit = "W", content = line))
        compose.onNodeWithText("Boiler").assertExists()
        compose.onNodeWithText("Garage").assertExists()
        compose.onNodeWithText("Min").assertExists()
        compose.onNodeWithText("Avg").assertExists()
        compose.onNodeWithText("Max").assertExists()
        compose.onNodeWithText("112 W").assertExists()
    }

    @Test
    fun `no title shows the house`() {
        show(HistoryUi(content = ChartContent.NoData))
        compose.onNodeWithText("House").assertExists()
    }

    @Test
    fun `the bars show their total`() {
        val days = DailyTotals(listOf(DayTotal(1_760_000_000L, 5.0), DayTotal(1_760_086_400L, 7.5)))
        show(HistoryUi(range = HistoryRange.D7, energy = true, toggle = true, mode = ChartMode.DAILY, content = ChartContent.Bars(days, 12.5)))
        compose.onNodeWithText("12.5 kWh over 2 days").assertExists()
    }

    @Test
    fun `each message state shows its text`() {
        show(HistoryUi(content = ChartContent.NoData))
        compose.onNodeWithText("No data in this period").assertExists()
    }

    @Test
    fun `not recorded, not allowed and missing`() {
        show(HistoryUi(content = ChartContent.NotRecorded))
        compose.onNodeWithText("No history recorded for this device").assertExists()
    }

    @Test
    fun `not allowed`() {
        show(HistoryUi(content = ChartContent.NotAllowed))
        compose.onNodeWithText("Add the history host to the gateway key").assertExists()
    }

    @Test
    fun `a missing entity shows not recorded even while loading`() {
        show(HistoryUi(missing = true, content = ChartContent.Loading))
        compose.onNodeWithText("No history recorded for this device").assertExists()
    }

    @Test
    fun `unavailable offers a retry`() {
        show(HistoryUi(content = ChartContent.Unavailable))
        compose.onNodeWithText("History unavailable").assertExists()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `the mode toggle is absent on 24 h and present on 7 d for energy`() {
        show(HistoryUi(energy = true, toggle = true, range = HistoryRange.H24, content = ChartContent.NoData))
        compose.onNodeWithText("kWh per day").assertDoesNotExist()
    }

    @Test
    fun `the mode toggle shows on 7 d and picks daily`() {
        show(HistoryUi(energy = true, toggle = true, range = HistoryRange.D7, content = ChartContent.NoData))
        compose.onNodeWithText("Power").assertExists()
        compose.onNodeWithText("kWh per day").performClick()
        assertEquals(listOf(ChartMode.DAILY), modes)
    }

    @Test
    fun `no toggle without energy`() {
        show(HistoryUi(energy = false, range = HistoryRange.D7, content = ChartContent.NoData))
        compose.onNodeWithText("kWh per day").assertDoesNotExist()
    }

    @Test
    fun `a range chip calls onRange`() {
        show(HistoryUi(content = ChartContent.NoData))
        compose.onNodeWithText("30 d").performClick()
        assertEquals(listOf(HistoryRange.D30), ranges)
    }

    @Test
    fun `back calls onBack`() {
        show(HistoryUi(content = ChartContent.NoData))
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }
}
