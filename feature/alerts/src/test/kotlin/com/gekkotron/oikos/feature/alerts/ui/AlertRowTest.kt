package com.gekkotron.oikos.feature.alerts.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.demo.DemoAlerts
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class AlertRowTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private val alerts = DemoAlerts.all(now)
    private val clicks = mutableListOf<String>()

    private fun show(index: Int) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) { AlertRow(alerts[index], now, onClick = { clicks += alerts[index].id }) }
    }

    @Test
    fun `a row shows the title, the body and the relative time`() {
        show(0)
        compose.onNodeWithText("Front door opened").assertExists()
        compose.onNodeWithText("The entrance door opened while the alarm was armed.").assertExists()
        compose.onNodeWithText("2", substring = true).assertExists()
    }

    @Test
    fun `a resolved alert is labelled Resolved`() {
        show(2)
        compose.onNodeWithText("Resolved", substring = true).assertExists()
    }

    @Test
    fun `an unresolved alert has no Resolved label`() {
        show(0)
        compose.onNodeWithText("Resolved", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a click is reported`() {
        show(1)
        compose.onNodeWithText("Person in the garden").performClick()
        assertEquals(listOf("demo-camera"), clicks)
    }
}
