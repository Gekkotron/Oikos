package com.gekkotron.oikos.feature.voice.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr")
class SpokenViewScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private fun show(choice: ThemeChoice, speakable: Boolean, onRepeat: () -> Unit = {}) = rule.setContent {
        OikosTheme(choice) { Column { SpokenView("Le salon est à 21 degrés", speakable, onRepeat) } }
    }

    @Test fun spokenView_light() {
        var repeated = false
        show(ThemeChoice.WARM_MINIMAL, true) { repeated = true }
        rule.onNodeWithText("Le salon est à 21 degrés").assertExists()
        rule.onNodeWithText("Répéter").performClick()
        assert(repeated)
    }

    @Test fun spokenView_dark() {
        show(ThemeChoice.NIGHT_GLASS, true)
        rule.onNodeWithText("Le salon est à 21 degrés").assertExists()
        rule.onNodeWithText("Répéter").assertExists()
    }

    @Test fun spokenView_text_only_when_not_speakable_light() {
        show(ThemeChoice.WARM_MINIMAL, false)
        rule.onNodeWithText("Le salon est à 21 degrés").assertExists()
        rule.onNodeWithText("Répéter").assertDoesNotExist()
    }
}
