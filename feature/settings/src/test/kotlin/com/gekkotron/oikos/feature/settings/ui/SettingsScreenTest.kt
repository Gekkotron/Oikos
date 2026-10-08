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
import androidx.compose.ui.test.performTextInput
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

    private val direct = ConnectionSummary.Direct("192.168.1.91:1883", tls = true, ws = false, username = "oikos", prefix = "homeassistant", gatewayUrl = "https://gw.example", historyUrl = null, historyDefault = "http://192.168.1.91:8090")

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

    private val savedFrigate = mutableListOf<Triple<String, String, String>>()
    private var frigateAccepts = true

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
        compose.onNodeWithText("Automatic (http://192.168.1.91:8090)").performClick()
        settle()
    }

    @Test
    fun `an automatic history url shows the derived address and tapping it opens the dialog, Cancel closes it`() {
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
                "nope", failed, hint = "http://h:8090", onTextChange = {},
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
    fun `the dialog hints at the derived address`() {
        showContent(accept = true) {}
        compose.onNodeWithText("http://h:8090").assertExists()
    }

    @Test
    fun `an override is shown as is`() {
        show(SettingsUi(connection = direct.copy(historyUrl = "http://nas:9000")))
        compose.onNodeWithText("http://nas:9000").assertExists()
    }

    @Test
    fun `an accepted save dismisses`() {
        var dismissed = 0
        showContent(accept = true) { dismissed++ }
        compose.onNodeWithText("Save").performClick()
        assertEquals(1, dismissed)
    }

    // Same hand-driven clock as showHistory: an open dialog never lets Robolectric report Compose idle.
    private fun showFrigate(connection: ConnectionSummary.Direct) {
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                SettingsScreen(
                    SettingsUi(connection = connection), version = "0.12 · debug", route = null,
                    onTheme = {}, onRouteChange = {}, onSetUpAgain = {}, onOpenRooms = {}, onOpenLicences = {}, onBack = {},
                    onSaveFrigate = { u, n, p -> savedFrigate += Triple(u, n, p); frigateAccepts },
                )
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Frigate").performScrollTo()
    }

    @Test
    fun `an unset frigate shows Not set and the dialog starts from the generic default`() {
        showFrigate(direct)
        compose.onNodeWithText("Not set").performClick()
        settle()
        compose.onNodeWithText("http://frigate:8971").assertExists()
        compose.onNodeWithText("e.g. http://frigate:8971 (login) or http://frigate:5000 (no login)").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        settle()
    }

    @Test
    fun `a configured frigate shows its url and user, and save reports the three fields`() {
        showFrigate(direct.copy(frigateUrl = "http://192.168.1.91:8971", frigateUser = "admin", frigateHasPassword = true))
        compose.onNodeWithText("http://192.168.1.91:8971").performClick()
        settle()
        compose.onNodeWithText("Unchanged").assertExists()
        compose.onNodeWithText("Save").performClick()
        settle()
        assertEquals(listOf(Triple("http://192.168.1.91:8971", "admin", "")), savedFrigate)
    }

    // Stateless body, like the history one: an open dialog window never lets Robolectric go idle.
    @Test
    fun `a refused frigate save keeps the dialog open with the error`() {
        var dismissed = 0
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                var failed by remember { mutableStateOf(false) }
                FrigateDialogContent(
                    "nope", "", initialUser = "", password = "", hasPassword = false, failed = failed,
                    onUrl = {}, onUser = {}, onPassword = {},
                    onSave = { if (frigateAccepts) dismissed++ else failed = true },
                    onCancel = {},
                )
            }
        }
        frigateAccepts = false
        compose.onNodeWithText("Use http(s)://host[:port], nothing after it").assertDoesNotExist()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Use http(s)://host[:port], nothing after it").assertExists()
        assertEquals(0, dismissed)
    }

    @Test
    fun `the Unchanged hint shows for the stored user only`() {
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                var user by remember { mutableStateOf("admin") }
                FrigateDialogContent(
                    "http://frigate:8971", user, initialUser = "admin", password = "", hasPassword = true, failed = false,
                    onUrl = {}, onUser = { user = it }, onPassword = {}, onSave = {}, onCancel = {},
                )
            }
        }
        compose.onNodeWithText("Unchanged").assertExists()
        compose.onNodeWithText("admin").performTextInput("x")
        compose.onNodeWithText("Unchanged").assertDoesNotExist()
    }

    @Test
    fun `a frigate user without a password shows no Login line`() {
        showFrigate(direct.copy(frigateUrl = "http://192.168.1.91:8971", frigateUser = "admin", frigateHasPassword = false))
        compose.onNodeWithText("Login: admin").assertDoesNotExist()
    }
}
