package com.gekkotron.oikos.feature.voice.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.gekkotron.oikos.feature.voice.ExecutionResult
import com.gekkotron.oikos.feature.voice.VoiceSessionState
import com.gekkotron.oikos.voice.VoiceAction
import com.gekkotron.oikos.voice.VoiceIntent
import com.gekkotron.oikos.voice.VoiceSource
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr")
class VoiceSheetScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `listening shows the pulsing mic and the partial text`() {
        val state = MutableStateFlow<VoiceSessionState>(VoiceSessionState.Listening("ouvre les vo"))
        rule.setContent { VoiceSheet(state = state, onPick = {}, onConfirm = {}, onCode = {}, onCancel = {}) }
        rule.onNodeWithText("ouvre les vo").assertExists()
    }

    @Test fun `understood shows the Faux button`() {
        val state = MutableStateFlow<VoiceSessionState>(
            VoiceSessionState.Done(
                ExecutionResult(
                    intent = VoiceIntent(VoiceAction.OPEN, listOf("x")),
                    source = VoiceSource.RULES,
                    succeeded = listOf("x"),
                    failed = emptyList(),
                )
            )
        )
        rule.setContent { VoiceSheet(state = state, onPick = {}, onConfirm = {}, onCode = {}, onCancel = {}) }
        rule.onNodeWithText("✗ Faux").assertExists()
    }

    @Test fun `confirm shows Disarm question for an alarm`() {
        val state = MutableStateFlow<VoiceSessionState>(
            VoiceSessionState.ConfirmNeeded(
                intent = VoiceIntent(VoiceAction.DISARM, listOf("alarm")),
                source = VoiceSource.RULES,
                codeFormat = null,
            )
        )
        var confirmed = false
        rule.setContent { VoiceSheet(state = state, onPick = {}, onConfirm = { confirmed = it }, onCode = {}, onCancel = {}) }
        rule.onNodeWithText("Désarmer l'alarme ?").assertExists()
        rule.onNodeWithText("Désarmer").performClick()
        assert(confirmed)
    }
}
