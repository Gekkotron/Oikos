package com.gekkotron.oikos.feature.onboarding.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class NotificationsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var enabled = 0
    private var skipped = 0

    private fun show(canAsk: Boolean) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) { NotificationsScreen(canAsk, onEnable = { enabled++ }, onSkip = { skipped++ }) }
    }

    @Test
    fun `it explains the alerts`() {
        show(canAsk = true)
        compose.onNodeWithText("Stay informed").assertExists()
        compose.onNodeWithText("alarm", substring = true).assertExists()
    }

    @Test
    fun `on Android 13 and later the button asks for notifications`() {
        show(canAsk = true)
        compose.onNodeWithText("Turn on notifications").performClick()
        assertEquals(1, enabled)
    }

    @Test
    fun `before Android 13 there is nothing to ask and the button just continues`() {
        show(canAsk = false)
        compose.onNodeWithText("Turn on notifications").assertDoesNotExist()
        compose.onNodeWithText("Continue").performClick()
        assertEquals(1, enabled)
    }

    @Test
    fun `Not now skips`() {
        show(canAsk = true)
        compose.onNodeWithText("Not now").performClick()
        assertEquals(1, skipped)
        assertEquals(0, enabled)
    }
}
