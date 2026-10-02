package com.gekkotron.oikos.feature.settings.ui

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.data.ThemePreference
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.settings.presentation.ConnectionSummary
import com.gekkotron.oikos.feature.settings.presentation.SettingsUi
import com.gekkotron.oikos.transport.Route
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val themes = mutableListOf<ThemePreference>()
    private var setUpAgain = 0
    private var rooms = 0
    private var licences = 0

    private val direct = ConnectionSummary.Direct("192.168.1.91:1883", tls = true, ws = false, username = "oikos", prefix = "homeassistant", gatewayUrl = "https://gw.example")

    private fun show(state: SettingsUi = SettingsUi(connection = direct), route: Route? = Route.AUTO) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) {
            SettingsScreen(
                state, version = "0.12 · debug", route = route,
                onTheme = { themes += it }, onRouteChange = {}, onSetUpAgain = { setUpAgain++ },
                onOpenRooms = { rooms++ }, onOpenLicences = { licences++ }, onBack = {},
            )
        }
    }

    @Test
    fun `theme rows show the choice and report a pick`() {
        show()
        compose.onNodeWithText("Follow the system").assertIsSelected()
        compose.onNodeWithText("Warm Minimal").performClick()
        assertEquals(listOf(ThemePreference.WARM_MINIMAL), themes)
    }

    @Test
    fun `connection summary shows the broker and the gateway, and the route picker`() {
        show()
        compose.onNodeWithText("192.168.1.91:1883").performScrollTo().assertExists()
        compose.onNodeWithText("https://gw.example").performScrollTo().assertExists()
        compose.onNodeWithText("Automatic").performScrollTo().assertIsSelected()
    }

    @Test
    fun `no gateway, no route picker`() {
        show(route = null)
        compose.onNodeWithText("Automatic").assertDoesNotExist()
    }

    @Test
    fun `set up again asks first, cancel does nothing and confirm calls back once`() {
        show()
        compose.onNodeWithText("Set up connection again").performScrollTo().performClick()
        compose.onNodeWithText("Set up again?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, setUpAgain)
        compose.onNodeWithText("Set up connection again").performScrollTo().performClick()
        compose.onNodeWithText("Set up again").performClick()
        assertEquals(1, setUpAgain)
        compose.onNodeWithText("Set up again?").assertDoesNotExist()
    }

    @Test
    fun `rooms and licences rows navigate, version is shown`() {
        show()
        compose.onNodeWithText("Rooms").performScrollTo().performClick()
        compose.onNodeWithText("Open-source licences").performScrollTo().performClick()
        compose.onNodeWithText("Oikos 0.12 · debug").performScrollTo().assertExists()
        assertEquals(1, rooms)
        assertEquals(1, licences)
    }

    @Test
    fun `no profile shows no connection rows and does not crash`() {
        show(SettingsUi(connection = null), route = null)
        compose.onNodeWithText("Broker").assertDoesNotExist()
        compose.onNodeWithText("Set up connection again").performScrollTo().assertExists()
    }
}
