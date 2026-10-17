package com.gekkotron.oikos.feature.settings.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.settings.presentation.VoiceSettingsUi
import com.gekkotron.oikos.feature.voice.TtsAvailability
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class VoiceSectionScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(tts: TtsAvailability) = compose.setContent {
        OikosTheme(ThemeChoice.WARM_MINIMAL) { VoiceSection(VoiceSettingsUi(ttsAvailability = tts), onToggle = {}) }
    }

    @Test
    fun voiceSection_tts_ready_light() {
        show(TtsAvailability.Ready)
        compose.onNodeWithText("Response voice").assertExists()
        compose.onNodeWithText("available").assertExists()
        compose.onNodeWithText("Download").assertDoesNotExist()
    }

    @Test
    fun voiceSection_tts_language_missing_shows_download_light() {
        show(TtsAvailability.LanguageMissing)
        compose.onNodeWithText("language pack missing").assertExists()
        compose.onNodeWithText("Download").assertExists()
    }

    @Test
    fun voiceSection_tts_engine_missing_light() {
        show(TtsAvailability.EngineMissing)
        compose.onNodeWithText("engine not installed").assertExists()
        compose.onNodeWithText("Download").assertDoesNotExist()
    }
}
