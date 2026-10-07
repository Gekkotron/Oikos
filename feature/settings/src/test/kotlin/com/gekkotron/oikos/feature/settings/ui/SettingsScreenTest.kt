package com.gekkotron.oikos.feature.settings.ui

import androidx.compose.ui.test.assertIsNotEnabled
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
import com.gekkotron.oikos.push.PushStatus
import com.gekkotron.oikos.transport.Route
import java.time.Instant
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

    private var registerAgain = 0
    private var channels = 0

    private fun showPush(status: PushStatus) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) {
            SettingsScreen(
                SettingsUi(connection = direct, push = status), version = "0.12 · debug", route = Route.AUTO,
                onTheme = {}, onRouteChange = {}, onSetUpAgain = {}, onOpenRooms = {}, onOpenLicences = {}, onBack = {},
                onRegisterAgain = { registerAgain++ }, onOpenChannels = { channels++ },
            )
        }
    }

    @Test
    fun `the push row shows each status in words`() {
        showPush(PushStatus.Registered(Instant.now().minusSeconds(120)))
        compose.onNodeWithText("Registered · ", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("2 min", substring = true).assertExists()
    }

    @Test
    fun `waiting`() {
        showPush(PushStatus.Waiting)
        compose.onNodeWithText("Waiting for connection").performScrollTo().assertExists()
    }

    @Test
    fun `a failure carries its reason`() {
        showPush(PushStatus.Failed("broker did not answer in time"))
        compose.onNodeWithText("Failed: broker did not answer in time").performScrollTo().assertExists()
    }

    @Test
    fun `notifications off and the demo have their own words`() {
        showPush(PushStatus.NotificationsOff)
        compose.onNodeWithText("Notifications off").performScrollTo().assertExists()
    }

    @Test
    fun `the demo says it is not available`() {
        showPush(PushStatus.Demo)
        compose.onNodeWithText("Not available in demo").performScrollTo().assertExists()
    }

    @Test
    fun `tapping the row opens the sheet, Register again closes it and reports`() {
        showPush(PushStatus.Waiting)
        compose.onNodeWithText("Push alerts").performScrollTo().performClick()
        compose.onNodeWithText("Register again").performClick()
        assertEquals(1, registerAgain)
        compose.onNodeWithText("Notification channels").assertDoesNotExist()
    }

    @Test
    fun `Notification channels reports and closes`() {
        showPush(PushStatus.Waiting)
        compose.onNodeWithText("Push alerts").performScrollTo().performClick()
        compose.onNodeWithText("Notification channels").performClick()
        assertEquals(1, channels)
    }

    @Test
    fun `in the demo Register again is disabled`() {
        showPush(PushStatus.Demo)
        compose.onNodeWithText("Push alerts").performScrollTo().performClick()
        compose.onNodeWithText("Register again").assertIsNotEnabled()
    }
}
