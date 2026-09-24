package com.gekkotron.oikos

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The real graph: ProfileRepository → ActiveTransport → DemoBroker → MqttDiscoverySource → Home. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@Config(application = HiltTestApplication::class, qualifiers = "w411dp-h914dp")
@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun openDemo() {
        compose.waitUntilAtLeastOneExists(hasText("Try the demo"), timeoutMillis = 10_000)
        compose.onNodeWithText("Try the demo").performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription("Floor lamp", substring = true), timeoutMillis = 10_000)
    }

    @Test
    fun `fresh install opens onboarding and the demo shows the demo house`() {
        openDemo()
        compose.onNodeWithText("Demo").assertExists()
        compose.waitUntilAtLeastOneExists(hasText("Living room"), timeoutMillis = 10_000)
    }

    @Test
    fun `set up connection again returns to welcome`() {
        openDemo()
        compose.onNodeWithText("Demo").performClick()
        compose.onNodeWithText("Set up connection again").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Try the demo"), timeoutMillis = 10_000)
    }

    @Test
    fun `after set up again onboarding starts fresh and the demo opens again`() {
        openDemo()
        compose.onNodeWithText("Demo").performClick()
        compose.onNodeWithText("Set up connection again").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Try the demo"), timeoutMillis = 10_000)
        compose.onNodeWithText("Try the demo").assertIsEnabled()
        compose.onNodeWithText("Scan setup code").assertIsEnabled()
        compose.onNodeWithText("Enter manually").assertIsEnabled()
        openDemo()
        compose.onNodeWithText("Demo").assertExists()
    }
}
