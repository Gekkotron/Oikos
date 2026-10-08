package com.gekkotron.oikos.feature.settings.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
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

    private val direct = ConnectionSummary.Direct("192.168.1.91:1883", tls = true, ws = false, username = "oikos", prefix = "homeassistant", gatewayUrl = "https://gw.example", historyUrl = null)

    private fun show(state: SettingsUi = SettingsUi(connection = direct), route: Route? = Route.AUTO) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) {
            SettingsScreen(
                state, version = "0.12 · debug", route = route,
                onTheme = { themes += it }, onRouteChange = {}, onSetUpAgain = { setUpAgain++ },
                onOpenRooms = { rooms++ }, onOpenLicences = { licences++ }, onBack = {},
                onSaveHistoryUrl = { saveHistory(it) },
            )
        }
    }

    private val savedHistory = mutableListOf<String>()
    private var historyAccepts = false
    private fun saveHistory(text: String): Boolean { savedHistory += text; return historyAccepts }

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

    /** Drives the paused clock and lets the dialog window attach. */
    private fun settle() {
        repeat(3) {
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            compose.mainClock.advanceTimeBy(200)
        }
    }

    // The dialog's field recomposes once the window takes focus, and Robolectric then never reports Compose idle
    // while the dialog stays open; so the clock is driven by hand and each test ends as the dialog closes.
    private fun showHistory() {
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                SettingsScreen(
                    SettingsUi(connection = direct), version = "0.12 · debug", route = null,
                    onTheme = {}, onRouteChange = {}, onSetUpAgain = {}, onOpenRooms = {}, onOpenLicences = {}, onBack = {},
                    onSaveHistoryUrl = { saveHistory(it) },
                )
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("History service").performScrollTo()
        compose.onNodeWithText("Not set").performClick()
        settle()
    }

    @Test
    fun `an unset history url shows Not set and tapping it opens the dialog, Cancel closes it`() {
        showHistory()
        compose.onNodeWithText("Save").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        settle()
        compose.onNodeWithText("Save").assertDoesNotExist()
        assertEquals(emptyList(), savedHistory)
    }

    @Test
    fun `an accepted save closes the dialog`() {
        historyAccepts = true
        showHistory()
        compose.onNodeWithText("Save").performClick()
        settle()
        assertEquals(listOf(""), savedHistory)
        compose.onNodeWithText("Save").assertDoesNotExist()
    }

    @Test
    fun `a refused save is reported to the caller`() {
        showHistory()
        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf(""), savedHistory)
    }

    // The dialog body is stateless, mirrors HistoryUrlDialog's save rule, and needs no dialog window.
    private fun showContent(accept: Boolean, onDismiss: () -> Unit) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) {
            var failed by remember { mutableStateOf(false) }
            HistoryUrlDialogContent(
                "nope", failed, onTextChange = {},
                onSave = { if (accept) onDismiss() else failed = true },
                onCancel = {},
            )
        }
    }

    @Test
    fun `a refused save keeps the dialog open and shows the error`() {
        var dismissed = 0
        showContent(accept = false) { dismissed++ }
        compose.onNodeWithText("Use http(s)://host[:port], nothing after it").assertDoesNotExist()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Use http(s)://host[:port], nothing after it").assertExists()
        compose.onNodeWithText("Save").assertExists()
        assertEquals(0, dismissed)
    }

    @Test
    fun `an accepted save dismisses`() {
        var dismissed = 0
        showContent(accept = true) { dismissed++ }
        compose.onNodeWithText("Save").performClick()
        assertEquals(1, dismissed)
    }
}
