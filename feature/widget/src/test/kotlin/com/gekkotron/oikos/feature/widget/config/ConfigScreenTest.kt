package com.gekkotron.oikos.feature.widget.config

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.Kind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class ConfigScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val kettle = PickerRow("a", "Kettle", "Living room", Kind.SWITCH, emptySet())
    private val alarm = PickerRow("al", "Alarm", "Living room", Kind.ALARM_PANEL, setOf(AlarmMode.HOME, AlarmMode.AWAY))

    private val toggled = mutableListOf<String>()
    private val modes = mutableListOf<AlarmMode>()
    private var done = 0
    private var opened = 0

    private fun show(state: ConfigUiState) = compose.setContent {
        OikosTheme(ThemeChoice.SYSTEM) {
            ConfigScreen(
                state,
                onSearch = {},
                onToggle = { toggled += it },
                onMove = { _, _ -> },
                onArmMode = { modes += it },
                onDone = { done++ },
                onOpenApp = { opened++ },
            )
        }
    }

    private fun ready(selected: List<String> = emptyList(), armMode: AlarmMode? = null) =
        ConfigUiState(loading = false, rows = listOf(alarm, kettle), allRows = listOf(alarm, kettle), selected = selected, armMode = armMode, fitCount = 4)

    @Test
    fun tapping_a_row_toggles_it() {
        show(ready())
        compose.onNodeWithText("Kettle").performClick()
        assertEquals(listOf("a"), toggled)
    }

    @Test
    fun done_saves() {
        show(ready(selected = listOf("a")))
        compose.onNodeWithText("Done").performClick()
        assertEquals(1, done)
    }

    @Test
    fun selected_alarm_offers_its_arm_modes() {
        show(ready(selected = listOf("al"), armMode = AlarmMode.AWAY))
        compose.onNodeWithText("Away").assertIsSelected()
        compose.onNodeWithText("Home").performClick()
        assertEquals(listOf(AlarmMode.HOME), modes)
    }

    @Test
    fun offline_banner_is_shown() {
        show(ready().copy(offline = true))
        compose.onNodeWithText("Can’t reach your home; showing the devices Oikos last saw.").assertExists()
    }

    @Test
    fun not_configured_offers_to_open_the_app() {
        show(ConfigUiState(loading = false, notConfigured = true))
        compose.onNodeWithText("Open Oikos").performClick()
        assertEquals(1, opened)
    }
}
