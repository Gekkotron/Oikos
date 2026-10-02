package com.gekkotron.oikos.feature.settings.licences

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
class LicencesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val json = """
        {"metadata":{"generated":"2026-10-03"},
         "libraries":[
           {"uniqueId":"rsms:inter","artifactVersion":"variable","name":"Inter","licenses":["OFL-1.1"]},
           {"uniqueId":"androidx.core:core","artifactVersion":"1.17.0","name":"Core","licenses":["Apache-2.0"]}],
         "licenses":{
           "OFL-1.1":{"hash":"OFL-1.1","name":"SIL Open Font License 1.1","content":"OFL body"},
           "Apache-2.0":{"hash":"Apache-2.0","name":"Apache License 2.0","content":"Apache body"}}}
    """.trimIndent()

    @Test
    fun `rows are parsed and sorted by name`() {
        val rows = licenceRows(json)
        assertEquals(listOf("Core", "Inter"), rows.map { it.name })
        assertEquals(LicenceRow("Inter", "variable", "SIL Open Font License 1.1", "OFL body", id = rows[1].id), rows[1])
    }

    @Test
    fun `tapping a row shows the licence text`() {
        compose.setContent { OikosTheme(ThemeChoice.NIGHT_GLASS) { LicencesScreen(licenceRows(json), onBack = {}) } }
        compose.onNodeWithText("Inter").assertExists()
        compose.onNodeWithText("SIL Open Font License 1.1", substring = true).assertExists()
        compose.onNodeWithText("Inter").performClick()
        compose.onNodeWithText("OFL body").assertExists()
    }

    @Test
    fun `broken json gives an empty list, not a crash`() {
        assertEquals(emptyList(), licenceRows("{oops"))
    }

    @Test
    fun `rows with the same name open their own licence text and close works`() {
        val rows = listOf(
            LicenceRow("Annotation", "1.0", "MIT", "first text", id = "a:one"),
            LicenceRow("Annotation", "1.0", "Apache", "second text", id = "b:two"),
        )
        compose.setContent { OikosTheme(ThemeChoice.NIGHT_GLASS) { LicencesScreen(rows, onBack = {}) } }
        compose.onAllNodesWithText("Annotation")[1].performClick()
        compose.onNodeWithText("second text").assertExists()
        compose.onNodeWithText("first text").assertDoesNotExist()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("second text").assertDoesNotExist()
    }

    @Test
    fun `a missing licence leaves no trailing separator`() {
        compose.setContent { OikosTheme(ThemeChoice.NIGHT_GLASS) { LicencesScreen(listOf(LicenceRow("Bare", "2.0", "", "")), onBack = {}) } }
        compose.onNodeWithText("2.0").assertExists()
    }
}
