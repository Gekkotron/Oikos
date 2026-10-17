package com.gekkotron.oikos.feature.voice.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.voice.PickStep
import com.gekkotron.oikos.feature.voice.VoiceSessionState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.voice.VoiceArea
import com.gekkotron.oikos.voice.VoiceCatalog
import com.gekkotron.oikos.voice.VoiceLang
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr")
class VoiceSheetDispatchTest {
    @get:Rule val rule = createComposeRule()

    private val catalog = VoiceCatalog(
        VoiceLang.FR,
        listOf(pickerEntity("l1", "Plafonnier", Kind.LIGHT, "salon")),
        listOf(VoiceArea("salon", "Salon", false, null)),
    )

    private fun show(state: VoiceSessionState) = rule.setContent {
        OikosTheme(ThemeChoice.WARM_MINIMAL) {
            VoiceSheet(
                state = MutableStateFlow(state),
                onPick = {}, onConfirm = {}, onCode = {}, onCancel = {},
                catalog = catalog,
            )
        }
    }

    @Test fun `spoken dispatches to SpokenView`() {
        show(VoiceSessionState.Spoken("Il fait 21 degrés", speakable = true, staleIds = emptyList()))
        rule.onNodeWithText("Il fait 21 degrés").assertExists()
        rule.onNodeWithText("Répéter").assertExists()
    }

    @Test fun `picking device dispatches to DevicePickerView`() {
        show(VoiceSessionState.Picking(PickStep.Device, "allume", "", suggestions = emptyList()))
        rule.onNodeWithText("Rechercher…").assertExists()
        rule.onNodeWithText("Plafonnier").assertExists()
    }

    @Test fun `picking action dispatches to ActionPickerView`() {
        show(VoiceSessionState.Picking(PickStep.Action, "allume", "", pickedId = "l1", suggestions = emptyList()))
        rule.onNodeWithText("Allumer").assertExists()
    }
}
