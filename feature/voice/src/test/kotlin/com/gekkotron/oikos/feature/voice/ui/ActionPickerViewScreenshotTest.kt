package com.gekkotron.oikos.feature.voice.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.voice.VoiceAction
import com.gekkotron.oikos.voice.VoiceEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr")
class ActionPickerViewScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private fun show(choice: ThemeChoice, entity: VoiceEntity, onPick: (VoiceAction, Boolean) -> Unit = { _, _ -> }) =
        rule.setContent {
            OikosTheme(choice) {
                Column { ActionPickerView(entity, "Salon", "allume", onPick, {}) }
            }
        }

    @Test fun actionPicker_light_light() {
        var picked: Pair<VoiceAction, Boolean>? = null
        show(ThemeChoice.WARM_MINIMAL, pickerEntity("l1", "Plafonnier", Kind.LIGHT, "salon")) { a, r -> picked = a to r }
        rule.onNodeWithText("Plafonnier").assertExists()
        rule.onNodeWithText("Salon").assertExists()
        rule.onNodeWithText("Lire l'état").assertExists()
        rule.onNodeWithText("Éteindre").assertExists()
        rule.onNodeWithText("Luminosité").assertDoesNotExist()
        rule.onNodeWithText("Allumer").performClick()
        assert(picked == VoiceAction.TURN_ON to true)
        rule.onNodeWithText("Mémoriser cette phrase").performClick()
        rule.onNodeWithText("Allumer").performClick()
        assert(picked == VoiceAction.TURN_ON to false)
    }

    @Test fun actionPicker_sensor_light() {
        show(ThemeChoice.WARM_MINIMAL, pickerEntity("s1", "Température", Kind.SENSOR, "salon"))
        rule.onNodeWithText("Lire la valeur").assertExists()
        rule.onNodeWithText("Lire l'état").assertDoesNotExist()
        rule.onNodeWithText("Allumer").assertDoesNotExist()
    }

    @Test fun actionPicker_cover_dark() {
        show(ThemeChoice.NIGHT_GLASS, pickerEntity("c1", "Volet baie", Kind.COVER, "salon"))
        listOf("Lire l'état", "Ouvrir", "Fermer", "Stop").forEach { rule.onNodeWithText(it).assertExists() }
        rule.onNodeWithText("Position").assertDoesNotExist()
    }

    @Test fun actionPicker_alarm_light() {
        val alarm: VoiceEntity = pickerEntity("a1", "Alarme", Kind.ALARM_PANEL, "salon")
            .copy(alarmModes = setOf(AlarmMode.HOME, AlarmMode.AWAY))
        show(ThemeChoice.WARM_MINIMAL, alarm)
        listOf("Lire l'état", "Armer (présent)", "Armer (absent)", "Désarmer").forEach { rule.onNodeWithText(it).assertExists() }
    }
}
