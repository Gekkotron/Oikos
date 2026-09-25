package com.gekkotron.oikos

import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.transport.testing.EmbeddedBroker
import com.hivemq.client.mqtt.datatypes.MqttQos
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.After
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

    private val brokers = mutableListOf<EmbeddedBroker>()

    @After
    fun tearDown() = brokers.forEach { it.close() }

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

    private val kettle = """{"name":"Kettle","unique_id":"kettle","command_topic":"k/set","state_topic":"k/state",
        "device":{"identifiers":["kettle"],"name":"Kitchen kettle"}}"""

    private fun connectToBroker(config: String) {
        val broker = EmbeddedBroker().also { it.start(); brokers += it }
        val peer = broker.peer()
        peer.publishWith().topic("homeassistant/switch/kettle/config").payload(config.encodeToByteArray())
            .qos(MqttQos.AT_LEAST_ONCE).retain(true).send()
        peer.disconnect()
        compose.waitUntilAtLeastOneExists(hasText("Enter manually"), timeoutMillis = 10_000)
        compose.onNodeWithText("Enter manually").performClick()
        compose.onNode(hasSetTextAction() and hasText("Host")).performTextInput("127.0.0.1")
        compose.onNode(hasSetTextAction() and hasText("Port")).performTextReplacement(broker.port.toString())
        compose.onNodeWithText("Connect").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText("Kitchen kettle"), timeoutMillis = 10_000)
        compose.onNodeWithText("Continue").performScrollTo().performClick()
    }

    /** The Rooms list is lazy: its last item may not be composed until the list scrolls to it. */
    private fun finishRooms(label: String) {
        compose.waitUntilAtLeastOneExists(hasText("Which room is each device in?"), timeoutMillis = 10_000)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performClick()
    }

    @Test
    fun `after the demo, a direct broker shows only its own house`() {
        openDemo()
        compose.onNodeWithText("Demo").performClick()
        compose.onNodeWithText("Set up connection again").performClick()
        connectToBroker(kettle)
        finishRooms("Skip")
        compose.waitUntilAtLeastOneExists(hasContentDescription("Kettle", substring = true), timeoutMillis = 10_000)
        compose.onAllNodes(hasContentDescription("Floor lamp", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `a new connection asks for rooms, and a room created there shows on Home`() {
        connectToBroker(kettle)
        compose.waitUntilAtLeastOneExists(hasText("Which room is each device in?"), timeoutMillis = 10_000)
        compose.onNodeWithText("Kitchen kettle").performClick()
        compose.onNode(hasSetTextAction() and hasText("New room")).performTextInput("Kitchen")
        compose.onNodeWithText("Add").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Done"), timeoutMillis = 10_000)
        finishRooms("Done")
        compose.waitUntilAtLeastOneExists(
            hasContentDescription("Kettle", substring = true) and hasContentDescription("Kitchen", substring = true),
            timeoutMillis = 10_000,
        )
        compose.onNodeWithText("Kitchen").assertExists()
    }

    @Test
    fun `the demo goes straight to Home, and Assign rooms opens the step and returns`() {
        openDemo()
        compose.onNodeWithText("Which room is each device in?").assertDoesNotExist()
        compose.onNodeWithText("Demo").performClick()
        compose.onNodeWithText("Assign rooms").performClick()
        finishRooms("Skip")
        compose.waitUntilAtLeastOneExists(hasContentDescription("Floor lamp", substring = true), timeoutMillis = 10_000)
    }
}
