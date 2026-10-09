package com.gekkotron.oikos.feature.voice

import app.cash.turbine.test
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.voice.VoiceAction
import com.gekkotron.oikos.voice.VoiceCatalog
import com.gekkotron.oikos.voice.VoiceCatalogBuilder
import com.gekkotron.oikos.voice.VoiceIntent
import com.gekkotron.oikos.voice.VoiceLang
import com.gekkotron.oikos.voice.VoiceShortcut
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSessionTest {

    private fun catalog(): VoiceCatalog = VoiceCatalogBuilder.build(
        lang = VoiceLang.FR,
        entities = listOf(
            entity("volet_salon_1", "Volet salon 1", Kind.COVER, "salon", position = true),
            entity("volet_salon_2", "Volet salon 2", Kind.COVER, "salon", position = true),
            entity("alarm", "Alarme", Kind.ALARM_PANEL, "entrance"),
        ),
        areas = listOf(area("salon", "Salon"), area("entrance", "Entrée")),
    )

    @Test fun `understood opens the shutters and emits Done`() = runTest {
        val speech = FakeSpeechCapture()
        val runner = FakeRunner(resultByEntity = mapOf("volet_salon_1" to CommandResult.Accepted, "volet_salon_2" to CommandResult.Accepted))
        val session = VoiceSession(catalog(), shortcuts = emptyList(), speech = speech, runner = runner, scope = backgroundScope)
        session.state.test {
            assertIs<VoiceSessionState.Listening>(awaitItem())
            session.start()
            speech.emitFinal("ouvre les volets du salon")
            assertIs<VoiceSessionState.Executing>(awaitItem())
            val done = assertIs<VoiceSessionState.Done>(awaitItem())
            assertEquals(setOf("volet_salon_1", "volet_salon_2"), done.result.succeeded.toSet())
            assertTrue(done.result.failed.isEmpty())
        }
    }

    @Test fun `not understood emits NotUnderstood with suggestions`() = runTest {
        val session = VoiceSession(catalog(), emptyList(), FakeSpeechCapture().also { it.scheduleFinal("salon") }, FakeRunner(), backgroundScope)
        session.state.test {
            skipItems(1)
            session.start()
            val not = assertIs<VoiceSessionState.NotUnderstood>(awaitItem())
            assertTrue(not.suggestions.isNotEmpty())
        }
    }

    @Test fun `alarm action lands in ConfirmNeeded`() = runTest {
        val session = VoiceSession(catalog(), emptyList(), FakeSpeechCapture().also { it.scheduleFinal("désarme l'alarme") }, FakeRunner(), backgroundScope)
        session.state.test {
            skipItems(1)
            session.start()
            val confirm = assertIs<VoiceSessionState.ConfirmNeeded>(awaitItem())
            assertEquals(VoiceAction.DISARM, confirm.intent.action)
        }
    }

    @Test fun `an alarm confirm with a disconnected broker ends in Error without sending`() = runTest {
        val runner = FakeRunner(mqttDown = true)
        val session = VoiceSession(catalog(), emptyList(), FakeSpeechCapture().also { it.scheduleFinal("désarme l'alarme") }, runner, backgroundScope)
        session.state.test {
            skipItems(1)
            session.start()
            skipItems(1) // ConfirmNeeded
            session.provideCode("1234")
            session.confirm(yes = true)
            skipItems(1) // Executing (brief)
            val err = assertIs<VoiceSessionState.Error>(awaitItem())
            assertEquals(ErrorKind.MQTT_DOWN, err.kind)
            assertTrue(runner.sent.isEmpty())
        }
    }

    @Test fun `partial failures appear in Done result`() = runTest {
        val runner = FakeRunner(resultByEntity = mapOf(
            "volet_salon_1" to CommandResult.Accepted,
            "volet_salon_2" to CommandResult.Rejected("timeout"),
        ))
        val session = VoiceSession(catalog(), emptyList(), FakeSpeechCapture().also { it.scheduleFinal("ouvre les volets du salon") }, runner, backgroundScope)
        session.state.test {
            skipItems(1)
            session.start()
            skipItems(1) // Executing
            val done = assertIs<VoiceSessionState.Done>(awaitItem())
            assertEquals(listOf("volet_salon_1"), done.result.succeeded)
            assertEquals(listOf("volet_salon_2" to "timeout"), done.result.failed)
        }
    }

    @Test fun `picking a suggestion learns the shortcut and executes`() = runTest {
        var saved: VoiceShortcut? = null
        val runner = FakeRunner(resultByEntity = mapOf("volet_salon_1" to CommandResult.Accepted, "volet_salon_2" to CommandResult.Accepted))
        val session = VoiceSession(catalog(), emptyList(), FakeSpeechCapture().also { it.scheduleFinal("quelque chose salon") }, runner, backgroundScope, onLearn = { saved = it })
        session.state.test {
            skipItems(1)
            session.start()
            val not = assertIs<VoiceSessionState.NotUnderstood>(awaitItem())
            val choice = not.suggestions.first { it.intent.action == VoiceAction.OPEN }
            session.pickSuggestion(choice, learnPhrase = "quelque chose salon")
            skipItems(1) // Executing
            assertIs<VoiceSessionState.Done>(awaitItem())
        }
        assertEquals("quelque chose salon", requireNotNull(saved).phrase)
    }
}
