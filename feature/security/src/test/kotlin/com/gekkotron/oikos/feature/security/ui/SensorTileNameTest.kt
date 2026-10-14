package com.gekkotron.oikos.feature.security.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.security.presentation.SensorRow
import com.gekkotron.oikos.model.SensorGroup
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class SensorTileNameTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = java.time.Instant.parse("2026-10-14T10:00:00Z")

    @Test
    fun raw_device_class_falls_back_to_group_label() {
        val smoke = row(name = "smoke", deviceClass = "smoke", group = SensorGroup.SMOKE, areaName = "Alicia")
        val co = row(name = "monoxide", deviceClass = "carbon_monoxide", group = SensorGroup.CO, areaName = "Living")
        val leak = row(name = "water_leak", deviceClass = "moisture", group = SensorGroup.LEAK, areaName = "Garage")
        val motion = row(name = "Couloir", deviceClass = "motion", group = SensorGroup.MOTION, areaName = "Living")

        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                SensorTile(smoke, now, onClick = {})
                SensorTile(co, now, onClick = {})
                SensorTile(leak, now, onClick = {})
                SensorTile(motion, now, onClick = {})
            }
        }

        compose.onNodeWithText("Smoke detector").assertIsDisplayed()
        compose.onNodeWithText("CO detector").assertIsDisplayed()
        compose.onNodeWithText("Leak detector").assertIsDisplayed()
        compose.onNodeWithText("Couloir").assertIsDisplayed()
    }

    @Test
    fun is_raw_class_name_matches_device_class_and_known_tokens() {
        assertTrue(isRawClassName(row(name = "smoke", deviceClass = "smoke", group = SensorGroup.SMOKE)))
        assertTrue(isRawClassName(row(name = "SMOKE", deviceClass = "smoke", group = SensorGroup.SMOKE)))
        assertTrue(isRawClassName(row(name = "monoxide", deviceClass = "carbon_monoxide", group = SensorGroup.CO)))
        assertTrue(isRawClassName(row(name = "water_leak", deviceClass = "moisture", group = SensorGroup.LEAK)))
        assertTrue(isRawClassName(row(name = "", deviceClass = null, group = SensorGroup.OPENING)))
        assertFalse(isRawClassName(row(name = "Couloir", deviceClass = "motion", group = SensorGroup.MOTION)))
        assertFalse(isRawClassName(row(name = "Porte terrasse", deviceClass = "door", group = SensorGroup.OPENING)))
    }

    private fun row(
        name: String,
        deviceClass: String?,
        group: SensorGroup,
        areaName: String? = null,
    ) = SensorRow(
        id = "id-$name",
        name = name,
        areaName = areaName,
        deviceClass = deviceClass,
        group = group,
        active = false,
        available = true,
    )
}
