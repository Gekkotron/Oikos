package com.gekkotron.oikos.feature.onboarding.ui

import android.Manifest
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.data.PayloadError
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.onboarding.FormField
import com.gekkotron.oikos.feature.onboarding.ManualForm
import com.gekkotron.oikos.feature.onboarding.TestFailure
import com.gekkotron.oikos.feature.onboarding.TestState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class OnboardingScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val calls = mutableListOf<String>()

    @Test
    fun `welcome buttons call back`() {
        compose.setContent { OikosTheme { WelcomeScreen(locked = false, busy = false, { calls += "scan" }, { calls += "manual" }, { calls += "demo" }) } }
        compose.onNodeWithText("Scan setup code").performClick()
        compose.onNodeWithText("Enter manually").performClick()
        compose.onNodeWithText("Try the demo").performClick()
        assertEquals(listOf("scan", "manual", "demo"), calls)
        compose.onNodeWithText("couldn't be unlocked", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Couldn't save the connection", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a failed save is shown on welcome and connect`() {
        var connect by mutableStateOf(false)
        compose.setContent {
            OikosTheme {
                if (connect) {
                    ConnectScreen(TestState.Connected(listOf("Kettle")), saving = false, {}, {}, {}, saveFailed = true)
                } else {
                    WelcomeScreen(locked = false, busy = false, {}, {}, {}, saveFailed = true)
                }
            }
        }
        compose.onNodeWithText("Couldn't save the connection", substring = true).assertExists()
        connect = true
        compose.onNodeWithText("Couldn't save the connection", substring = true).assertExists()
        compose.onNodeWithText("Continue").assertIsEnabled()
    }

    @Test
    fun `welcome shows the unlock notice when locked`() {
        compose.setContent { OikosTheme { WelcomeScreen(locked = true, busy = false, {}, {}, {}) } }
        compose.onNodeWithText("couldn't be unlocked", substring = true).assertExists()
    }

    @Test
    fun `manual shows field errors and the plain-text warning`() {
        compose.setContent {
            OikosTheme {
                ManualScreen(ManualForm(host = "mqtt.example.com", port = "0"), setOf(FormField.PORT), {}, { calls += "submit" }, {})
            }
        }
        compose.onNodeWithText("Port must be between 1 and 65535").assertExists()
        compose.onNodeWithText("doesn't look local", substring = true).assertExists()
        compose.onNodeWithText("Connect").performScrollTo().performClick()
        assertEquals(listOf("submit"), calls)
    }

    @Test
    fun `manual edits flow back through onChange`() {
        var form by mutableStateOf(ManualForm())
        compose.setContent { OikosTheme { ManualScreen(form, emptySet(), { form = it }, {}, {}) } }
        compose.onNodeWithText("TLS").performClick()
        assertTrue(form.tls)
    }

    @Test
    fun `connect shows progress, then a failure with retry`() {
        var test by mutableStateOf<TestState>(TestState.Connecting)
        compose.setContent { OikosTheme { ConnectScreen(test, saving = false, { calls += "retry" }, {}, {}) } }
        compose.onNodeWithText("Connecting…").assertExists()
        test = TestState.Failed(TestFailure.Rejected)
        compose.onNodeWithText("Username or password rejected.").assertExists()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `connect lists devices and continues`() {
        compose.setContent { OikosTheme { ConnectScreen(TestState.Connected(listOf("Kettle", "Lamp")), false, {}, {}, { calls += "continue" }) } }
        compose.onNodeWithText("Discovering… 2 devices").assertExists()
        compose.onNodeWithText("Kettle").assertExists()
        compose.onNodeWithText("Continue").assertIsEnabled().performClick()
        assertEquals(listOf("continue"), calls)
    }

    @Test
    fun `connect with no devices explains why and still allows continue`() {
        compose.setContent { OikosTheme { ConnectScreen(TestState.Connected(emptyList()), false, {}, {}, {}) } }
        compose.onNodeWithText("No devices announced yet", substring = true).assertExists()
        compose.onNodeWithText("Continue").assertIsEnabled()
    }

    @Test
    fun `connect caps the device list at 5 and shows a plus-more line`() {
        val devices = listOf("A", "B", "C", "D", "E", "F", "G", "H")
        compose.setContent { OikosTheme { ConnectScreen(TestState.Connected(devices), false, {}, {}, {}) } }
        compose.onNodeWithText("A").assertExists()
        compose.onNodeWithText("E").assertExists()
        compose.onNodeWithText("F").assertDoesNotExist()
        compose.onNodeWithText("+3 more").assertExists()
        compose.onNodeWithText("Continue").assertExists()
    }

    @Test
    fun `connect shows all devices with no more line when under the cap`() {
        val devices = listOf("A", "B", "C")
        compose.setContent { OikosTheme { ConnectScreen(TestState.Connected(devices), false, {}, {}, {}) } }
        compose.onNodeWithText("A").assertExists()
        compose.onNodeWithText("B").assertExists()
        compose.onNodeWithText("C").assertExists()
        compose.onNodeWithText("more", substring = true).assertDoesNotExist()
    }

    @Test
    fun `continue is disabled while saving`() {
        compose.setContent { OikosTheme { ConnectScreen(TestState.Connected(listOf("Kettle")), saving = true, {}, {}, {}) } }
        compose.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test
    fun `a camera permission granted in Settings is picked up on return`() {
        compose.setContent {
            OikosTheme { ScanScreen(null, {}, {}, {}, camera = { modifier, _ -> Box(modifier.testTag("camera")) }) }
        }
        compose.onNodeWithTag("camera").assertDoesNotExist()
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.CAMERA)
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("camera").assertExists()
    }

    @Test
    fun `scan shows the camera when granted and the error below it`() {
        compose.setContent {
            OikosTheme {
                ScanContent(CameraPermission.GRANTED, PayloadError.NotOikosCode, {}, {}, {}, {}) { modifier, _ ->
                    Box(modifier.testTag("camera"))
                }
            }
        }
        compose.onNodeWithTag("camera").assertExists()
        compose.onNodeWithText("isn't an Oikos setup code", substring = true).assertExists()
    }

    @Test
    fun `scan without permission offers settings and manual entry`() {
        compose.setContent {
            OikosTheme {
                ScanContent(CameraPermission.DENIED, null, {}, { calls += "manual" }, { calls += "settings" }, {}) { modifier, _ ->
                    Box(modifier.testTag("camera"))
                }
            }
        }
        compose.onNodeWithTag("camera").assertDoesNotExist()
        compose.onNodeWithText("Open settings").performClick()
        compose.onNodeWithText("Enter manually").performClick()
        assertEquals(listOf("settings", "manual"), calls)
    }

    @Test
    fun `connect says when it went through the gateway`() {
        compose.setContent { OikosTheme { ConnectScreen(TestState.Connected(listOf("Kettle"), viaGateway = true), false, {}, {}, {}) } }
        compose.onNodeWithText("Connected through the gateway", substring = true).assertExists()
    }

    @Test
    fun `gateway failures have their own texts`() {
        var test by mutableStateOf<TestState>(TestState.Failed(TestFailure.KeyRejected))
        compose.setContent { OikosTheme { ConnectScreen(test, false, {}, {}, {}) } }
        compose.onNodeWithText("didn't accept the key", substring = true).assertExists()
        test = TestState.Failed(TestFailure.ClockSkew)
        compose.onNodeWithText("automatic date and time", substring = true).assertExists()
    }

    @Test
    fun `secure window sets and clears FLAG_SECURE`() {
        var shown by mutableStateOf(true)
        compose.setContent { if (shown) SecureWindow() }
        val flags = { compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE }
        compose.waitForIdle()
        assertTrue(flags() != 0)
        shown = false
        compose.waitForIdle()
        assertEquals(0, flags())
    }
}
