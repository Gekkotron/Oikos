package com.gekkotron.oikos.feature.security.ui

import androidx.compose.material3.Text
import kotlin.test.assertTrue
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.feature.security.action
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.longClick
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.security.alarm
import com.gekkotron.oikos.feature.security.on
import com.gekkotron.oikos.feature.security.panel
import com.gekkotron.oikos.feature.security.sensor
import com.gekkotron.oikos.feature.security.presentation.SecurityUi
import com.gekkotron.oikos.feature.security.presentation.SecurityUiBuilder
import com.gekkotron.oikos.history.LastChange
import com.gekkotron.oikos.model.AlarmState
import androidx.compose.ui.test.assertContentDescriptionEquals
import java.time.Duration
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.transport.LinkState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class SecurityScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val hidden = mutableListOf<Pair<String, Boolean>>()

    private val now = java.time.Instant.parse("2026-10-14T10:00:00Z")

    private fun ui(entities: List<Entity>, states: List<EntityState> = emptyList()): SecurityUi = SecurityUiBuilder.build(
        entities.associateBy { it.uniqueId }, states.associateBy { it.uniqueId }, listOf(Area("garage", "Garage")), LinkState.Connecting,
    )

    private val sent = mutableListOf<Pair<String, Command>>()
    private val actionSettings = mutableListOf<Triple<String, Boolean, Boolean>>()

    private fun show(state: SecurityUi, at: java.time.Instant = now) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) {
            SecurityScreen(
                state,
                now = at,
                onSend = { id, c -> sent += id to c },
                onSetHidden = { id, h -> hidden += id to h },
                onSetAction = { id, s, a -> actionSettings += Triple(id, s, a) },
            )
        }
    }

    private val customized = mutableListOf<String>()

    private fun showWithCustomize(state: SecurityUi) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) {
            SecurityScreen(state, customizeSheet = { id, _ -> customized += id; Text("Customizing $id") })
        }
    }

    private fun showLive(initial: SecurityUi): MutableState<SecurityUi> {
        val holder = mutableStateOf(initial)
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) { SecurityScreen(holder.value, onSend = { id, c -> sent += id to c }) }
        }
        return holder
    }

    @Test
    fun `panel, summary and sensors`() {
        show(
            ui(
                listOf(panel(), sensor("garage_door", "garage_door", "Garage door", area = "garage"), sensor("motion", "motion", "Corridor"), sensor("door", "door", "Entrance")),
                listOf(alarm("alarm", AlarmState.ARMED_AWAY), on("garage_door", true), on("motion", true), on("door", false)),
            ),
        )
        compose.onNodeWithText("Armed · Away").assertExists()
        compose.onNodeWithText("1 open · 1 motion").assertExists()
        compose.onNodeWithText("Garage door").assertExists()
        compose.onNodeWithText("Closed").performScrollTo().assertExists()
    }

    @Test
    fun `a stale sensor row exposes the last known value`() {
        show(ui(listOf(sensor("door", "door", "Entrance")), listOf(on("door", true, stale = true))))
        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Last known value") and hasText("Entrance"),
        ).assertExists()
    }

    @Test
    fun `all clear and the no-panel card`() {
        show(ui(listOf(sensor("door", "door", "Entrance")), listOf(on("door", false))))
        compose.onNodeWithText("All clear").assertExists()
        compose.onNodeWithText("No alarm panel. Publish an MQTT alarm_control_panel to see it here.").assertExists()
    }

    @Test
    fun `nothing at all shows one empty state`() {
        show(ui(emptyList()))
        compose.onNodeWithText("No alarm panel or security sensor yet. Publish an MQTT alarm_control_panel, or pair door, motion, smoke or leak sensors.").assertExists()
        compose.onNodeWithText("All clear").assertDoesNotExist()
    }

    @Test
    fun `nothing is drawn while loading`() {
        show(SecurityUi())
        compose.onNodeWithText("All clear").assertDoesNotExist()
        compose.onNodeWithText("Recent alerts").assertDoesNotExist()
    }

    @Test
    fun `tapping a row lets it leave Security`() {
        show(ui(listOf(sensor("mailbox", "door", "Mailbox")), listOf(on("mailbox", false))))
        compose.onNodeWithText("Mailbox").performClick()
        compose.onNodeWithText("Show on Security").performClick()
        assertEquals(listOf("mailbox" to true), hidden)
    }

    @Test
    fun `unavailable and unknown sensors say so`() {
        show(ui(listOf(sensor("a", "smoke", "Kitchen smoke"), sensor("b", "moisture", "Pool")), listOf(on("a", false, available = false))))
        compose.onNodeWithText("Unavailable").assertExists()
        compose.onNodeWithText("—").assertExists()
    }

    @Test
    fun `sections head their tiles, and a tile shows name, room and state`() {
        show(
            ui(
                listOf(sensor("smoke", "smoke", "Kitchen smoke"), sensor("garage_door", "garage_door", "Garage door", area = "garage")),
                listOf(on("smoke", false), on("garage_door", true)),
            ),
        )
        compose.onNodeWithText("Fire").assertExists()
        compose.onNodeWithText("Openings").assertExists()
        compose.onNodeWithText("Motion").assertDoesNotExist()
        compose.onNode(hasText("Garage door") and hasText("Garage") and hasText("Open")).assertExists()
    }

    @Test
    fun `actions sit in their own section and an action alone is not the empty state`() {
        show(ui(listOf(action("siren", name = "Siren", area = "garage")), listOf(on("siren", false))))
        compose.onNodeWithText("Actions").assertExists()
        compose.onNode(hasText("Siren") and hasText("Garage") and hasText("Off")).assertExists()
        compose.onNodeWithText("No alarm panel or security sensor yet. Publish an MQTT alarm_control_panel, or pair door, motion, smoke or leak sensors.").assertDoesNotExist()
    }

    @Test
    fun `turning an action on asks first and sends only on confirm`() {
        show(ui(listOf(action("siren", name = "Siren")), listOf(on("siren", false))))
        compose.onNodeWithTag("action-siren").performClick()
        assertTrue(sent.isEmpty())
        compose.onNodeWithText("Turn on Siren?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(sent.isEmpty())
        compose.onNodeWithTag("action-siren").performClick()
        compose.onNodeWithText("Turn on").performClick()
        assertEquals(listOf<Pair<String, Command>>("siren" to Command.TurnOn()), sent)
        compose.onNodeWithText("Turn on Siren?").assertDoesNotExist()
    }

    @Test
    fun `turning an action off is immediate, and ask off fires at once`() {
        show(
            ui(
                listOf(action("siren", name = "Siren"), action("panic", Kind.BUTTON, name = "Panic", ui = UiOverride(securityAction = true, securityAsk = false))),
                listOf(on("siren", true)),
            ),
        )
        compose.onNodeWithTag("action-siren").performClick()
        compose.onNodeWithTag("action-panic").performClick()
        assertEquals(listOf<Pair<String, Command>>("siren" to Command.TurnOff, "panic" to Command.Press), sent)
    }

    @Test
    fun `the confirm dialog closes when its action goes unavailable`() {
        val entities = listOf(action("siren", name = "Siren"))
        val live = showLive(ui(entities, listOf(on("siren", false))))
        compose.onNodeWithTag("action-siren").performClick()
        compose.onNodeWithText("Turn on Siren?").assertExists()
        live.value = ui(entities, listOf(on("siren", false, available = false)))
        compose.onNodeWithText("Turn on Siren?").assertDoesNotExist()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `an unavailable action is not tappable but its sheet opens`() {
        show(ui(listOf(action("siren", name = "Siren")), listOf(on("siren", false, available = false))))
        compose.onNodeWithTag("action-siren").performClick()
        compose.onNodeWithText("Turn on Siren?").assertDoesNotExist()
        compose.onNodeWithTag("action-siren").performTouchInput { longClick() }
        compose.onNodeWithText("Ask before turning on").assertExists()
    }

    @Test
    fun `the action sheet saves ask and removes from Security`() {
        show(ui(listOf(action("siren", name = "Siren")), listOf(on("siren", false))))
        compose.onNodeWithTag("action-siren").performTouchInput { longClick() }
        compose.onNodeWithText("Ask before turning on").performClick()
        compose.onNodeWithText("Show on Security").performClick()
        assertEquals(listOf(Triple("siren", true, false), Triple("siren", false, true)), actionSettings)
    }

    @Test
    fun `a stale action skips the question and goes straight to the waiting message`() {
        show(ui(listOf(action("siren", name = "Siren")), listOf(on("siren", false, stale = true))))
        compose.onNodeWithTag("action-siren").performClick()
        compose.onNodeWithText("Turn on Siren?").assertDoesNotExist()
        // The view model refuses a stale send and shows "Waiting for the connection…" (SecurityViewModelTest).
        assertEquals(listOf<Pair<String, Command>>("siren" to Command.TurnOn()), sent)
    }

    @Test
    fun `Customize from a sensor sheet opens the customise sheet over Security`() {
        showWithCustomize(ui(listOf(sensor("mailbox", "door", "Mailbox")), listOf(on("mailbox", false))))
        compose.onNodeWithText("Mailbox").performClick()
        compose.onNodeWithText("Customize…").performClick()
        compose.onNodeWithText("Customizing mailbox").assertExists()
        compose.onNodeWithText("Show on Security").assertDoesNotExist()
        assertEquals("mailbox", customized.last())
    }

    @Test
    fun `Customize from an action sheet opens the customise sheet over Security`() {
        showWithCustomize(ui(listOf(action("siren", name = "Siren")), listOf(on("siren", false))))
        compose.onNodeWithTag("action-siren").performTouchInput { longClick() }
        compose.onNodeWithText("Customize…").performClick()
        compose.onNodeWithText("Customizing siren").assertExists()
        compose.onNodeWithText("Ask before turning on").assertDoesNotExist()
    }

    @Test
    fun `without a customise sheet the sheets offer no link`() {
        show(ui(listOf(sensor("mailbox", "door", "Mailbox")), listOf(on("mailbox", false))))
        compose.onNodeWithText("Mailbox").performClick()
        compose.onNodeWithText("Customize…").assertDoesNotExist()
    }

    @Test
    fun `the alerts slot shows under the sensors`() {
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                SecurityScreen(ui(listOf(sensor("door", "door", "Entrance")), listOf(on("door", false))), alertsSection = { Text("alerts slot") })
            }
        }
        compose.onNodeWithText("alerts slot").performScrollTo().assertExists()
    }

    @Test
    fun `the alerts slot shows even when the house has no panel or sensor`() {
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) { SecurityScreen(ui(emptyList()), alertsSection = { Text("alerts slot") }) }
        }
        compose.onNodeWithText("alerts slot").assertExists()
    }

    @Test
    fun `without a slot there is no alerts section`() {
        show(ui(listOf(sensor("door", "door", "Entrance")), listOf(on("door", false))))
        compose.onNodeWithText("Recent alerts").assertDoesNotExist()
    }

    private fun withSince(server: LastChange, stale: Boolean = false, available: Boolean = true) = SecurityUiBuilder.build(
        mapOf("door" to sensor("door", "door", name = "Front door")),
        mapOf("door" to on("door", false, available = available, stale = stale)),
        emptyList(), LinkState.Connecting, mapOf("door" to server),
    )

    @Test
    fun `the tile shows how long, and TalkBack reads it in words`() {
        show(withSince(LastChange.At(now.minusSeconds(2 * 3_600 + 300))))
        compose.onNodeWithText("Closed · 2 h", useUnmergedTree = true).assertContentDescriptionEquals("Closed for 2 hours")
    }

    @Test
    fun `over the window reads 30+ d`() {
        show(withSince(LastChange.OlderThan(Duration.ofDays(30))))
        compose.onNodeWithText("Closed · 30+ d", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `no duration on a stale or unavailable tile, nor without history`() {
        show(withSince(LastChange.At(now.minusSeconds(600)), stale = true))
        compose.onNodeWithText("Closed", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Closed · 10 min", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `an unavailable tile shows no duration`() {
        show(withSince(LastChange.At(now.minusSeconds(600)), available = false))
        compose.onNodeWithText("Closed · 10 min", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a tile without since looks as before`() {
        show(ui(listOf(sensor("door", "door", name = "Front door")), listOf(on("door", false))))
        compose.onNodeWithText("Closed", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the sheet gives the exact time`() {
        show(withSince(LastChange.At(java.time.Instant.parse("2026-10-14T06:42:00Z"))))
        compose.onNodeWithTag("sensor-door").performClick()
        compose.onNode(hasText("Since ", substring = true)).assertExists()
    }

    @Test
    fun `the sheet says no change in 30 days`() {
        show(withSince(LastChange.OlderThan(Duration.ofDays(30))))
        compose.onNodeWithTag("sensor-door").performClick()
        compose.onNodeWithText("No change in 30 days").assertExists()
    }
}
