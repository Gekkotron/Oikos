package com.gekkotron.oikos.designsystem.components

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.transport.Route
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class RoutePickerTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `shows the selected route and reports a pick`() {
        val picked = mutableListOf<Route>()
        compose.setContent { OikosTheme(ThemeChoice.NIGHT_GLASS) { RoutePicker(Route.AUTO, onSelect = { picked += it }) } }
        compose.onNodeWithText("Connection").assertExists()
        compose.onNodeWithText("Automatic").assertIsSelected()
        compose.onNodeWithText("Gateway").performClick()
        assertEquals(listOf(Route.GATEWAY), picked)
    }
}
