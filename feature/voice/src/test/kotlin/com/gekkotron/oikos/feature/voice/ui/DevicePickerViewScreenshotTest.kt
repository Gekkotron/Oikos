package com.gekkotron.oikos.feature.voice.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.voice.PickStep
import com.gekkotron.oikos.feature.voice.VoiceSessionState
import com.gekkotron.oikos.model.CodeFormat
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.voice.VoiceArea
import com.gekkotron.oikos.voice.VoiceCatalog
import com.gekkotron.oikos.voice.VoiceEntity
import com.gekkotron.oikos.voice.VoiceLang
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal fun pickerEntity(id: String, name: String, kind: Kind, area: String) = VoiceEntity(
    uniqueId = id, displayName = name, kind = kind, areaId = area, deviceClass = null,
    brightness = false, position = false, codeFormat = CodeFormat.NUMBER, alarmModes = emptySet(),
    codeArmRequired = false, codeDisarmRequired = false,
)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr")
class DevicePickerViewScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val catalog = VoiceCatalog(
        VoiceLang.FR,
        listOf(
            pickerEntity("l1", "Plafonnier", Kind.LIGHT, "salon"),
            pickerEntity("c1", "Volet baie", Kind.COVER, "salon"),
            pickerEntity("l2", "Chevet", Kind.LIGHT, "chambre"),
            pickerEntity("s1", "Température", Kind.SENSOR, "chambre"),
        ),
        listOf(VoiceArea("salon", "Salon", false, "living"), VoiceArea("chambre", "Chambre", false, "bedroom")),
    )
    private val hints = mapOf("l1" to "60 %", "c1" to "Ouvert", "s1" to "21°")

    private fun picking(filter: String) =
        VoiceSessionState.Picking(PickStep.Device, "allume", filter, null, emptyList())

    private fun show(choice: ThemeChoice, filter: String, onPick: (String) -> Unit = {}) = rule.setContent {
        OikosTheme(choice) {
            Column {
                DevicePickerView(picking(filter), catalog, hints, onPick, {}, {})
            }
        }
    }

    @Test fun devicePicker_default_light() {
        var picked: String? = null
        show(ThemeChoice.WARM_MINIMAL, "") { picked = it }
        rule.onNodeWithText("Salon").assertExists()
        rule.onNodeWithText("Chambre").assertExists()
        rule.onNodeWithText("Lumière · 60 %").assertExists()
        rule.onNodeWithText("Capteur · 21°").assertExists()
        rule.onNodeWithText("Plafonnier").performClick()
        assert(picked == "l1")
    }

    @Test fun devicePicker_default_dark() {
        show(ThemeChoice.NIGHT_GLASS, "")
        rule.onNodeWithText("Volet baie").assertExists()
        rule.onNodeWithText("Retour").assertExists()
    }

    @Test fun devicePicker_filtered_light() {
        show(ThemeChoice.WARM_MINIMAL, "lum")
        rule.onNodeWithText("Plafonnier").assertExists()
        rule.onNodeWithText("Chevet").assertExists()
        rule.onNodeWithText("Volet baie").assertDoesNotExist()
        rule.onNodeWithText("Température").assertDoesNotExist()
    }

    @Test fun devicePicker_no_match_shows_empty_state() {
        show(ThemeChoice.WARM_MINIMAL, "zzz")
        rule.onNodeWithText("Aucun résultat").assertExists()
    }
}
