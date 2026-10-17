package com.gekkotron.oikos.feature.voice

import app.cash.turbine.test
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.Kind
import android.speech.tts.TextToSpeech
import com.gekkotron.oikos.voice.Answer
import com.gekkotron.oikos.voice.VoiceAction
import com.gekkotron.oikos.voice.VoiceCatalog
import com.gekkotron.oikos.voice.VoiceCatalogBuilder
import com.gekkotron.oikos.voice.VoiceIntent
import com.gekkotron.oikos.voice.VoiceLang
import com.gekkotron.oikos.voice.VoiceShortcut
import com.gekkotron.oikos.voice.VoiceSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test fun `an unknown runner failure ends in Error without crashing`() = runTest {
        val runner = object : CommandRunner {
            override suspend fun run(intent: VoiceIntent, catalog: VoiceCatalog, code: String?, source: VoiceSource): ExecutionResult {
                throw RuntimeException("boom")
            }
            override suspend fun query(intent: VoiceIntent, catalog: VoiceCatalog): QueryResult = throw RuntimeException("boom")
        }
        val session = VoiceSession(catalog(), emptyList(), FakeSpeechCapture().also { it.scheduleFinal("ouvre les volets du salon") }, runner, backgroundScope)
        session.state.test {
            skipItems(1)
            session.start()
            skipItems(1) // Executing
            val err = assertIs<VoiceSessionState.Error>(awaitItem())
            assertEquals(ErrorKind.UNKNOWN, err.kind)
        }
    }

    @Test fun `onLearn failure does not block the chosen command from running`() = runTest {
        val runner = FakeRunner(resultByEntity = mapOf("volet_salon_1" to CommandResult.Accepted, "volet_salon_2" to CommandResult.Accepted))
        val session = VoiceSession(
            catalog = catalog(),
            shortcuts = emptyList(),
            speech = FakeSpeechCapture().also { it.scheduleFinal("quelque chose salon") },
            runner = runner,
            scope = backgroundScope,
            onLearn = { throw RuntimeException("save failed") },
        )
        session.state.test {
            skipItems(1)
            session.start()
            val not = assertIs<VoiceSessionState.NotUnderstood>(awaitItem())
            val choice = not.suggestions.first { it.intent.action == VoiceAction.OPEN }
            session.pickSuggestion(choice, learnPhrase = "quelque chose salon")
            skipItems(1) // Executing
            assertIs<VoiceSessionState.Done>(awaitItem())
        }
    }

    private class FakeTtsEngine(private val available: Int) : TtsEngine {
        val spoken = mutableListOf<String>()
        override fun setLanguage(locale: Locale): Int = available
        override fun isLanguageAvailable(locale: Locale): Int = available
        override fun speak(text: String, queueMode: Int): Int { spoken += text; return TextToSpeech.SUCCESS }
        override fun shutdown() {}
    }

    private fun tts(available: Int = TextToSpeech.LANG_AVAILABLE): Pair<TtsSpeaker, FakeTtsEngine> {
        val engine = FakeTtsEngine(available)
        var listener: TextToSpeech.OnInitListener? = null
        val speaker = TtsSpeaker(VoiceLang.FR) { l -> listener = l; engine }
        listener!!.onInit(TextToSpeech.SUCCESS)
        return speaker to engine
    }

    private val answer = Answer(spoken = "Les volets sont ouverts.", display = "Volets ouverts", speakable = true, staleIds = listOf("volet_salon_1"))

    private fun notUnderstoodSession(
        runner: FakeRunner = FakeRunner(),
        tts: TtsSpeaker? = null,
        onLearn: suspend (VoiceShortcut) -> Unit = {},
        scope: kotlinx.coroutines.CoroutineScope,
    ) = VoiceSession(catalog(), emptyList(), FakeSpeechCapture().also { it.scheduleFinal("quelque chose salon") }, runner, scope, onLearn, tts)

    @Test fun `a QUERY_STATE intent transitions to Spoken with the formatter sentence and speaks it once`() = runTest {
        val (speaker, engine) = tts()
        val runner = FakeRunner(queryAnswer = answer)
        val session = VoiceSession(catalog(), emptyList(), FakeSpeechCapture().also { it.scheduleFinal("est-ce que les volets du salon sont fermés") }, runner, backgroundScope, tts = speaker)
        session.state.test {
            skipItems(1)
            session.start()
            val spoken = assertIs<VoiceSessionState.Spoken>(awaitItem())
            assertEquals("Volets ouverts", spoken.text)
            assertTrue(spoken.speakable)
            assertEquals(listOf("volet_salon_1"), spoken.staleIds)
            assertTrue(runner.queried.single().entityIds.containsAll(listOf("volet_salon_1", "volet_salon_2")))
        }
        assertEquals(listOf("Les volets sont ouverts."), engine.spoken)
    }

    @Test fun `Spoken is skipped past speak when TTS availability is LanguageMissing`() = runTest {
        val (speaker, engine) = tts(TextToSpeech.LANG_MISSING_DATA)
        val runner = FakeRunner(queryAnswer = answer)
        val session = VoiceSession(catalog(), emptyList(), FakeSpeechCapture().also { it.scheduleFinal("est-ce que les volets du salon sont fermés") }, runner, backgroundScope, tts = speaker)
        session.state.test {
            skipItems(1)
            session.start()
            assertIs<VoiceSessionState.Spoken>(awaitItem())
        }
        assertTrue(engine.spoken.isEmpty())
    }

    @Test fun `onPickOther from NotUnderstood goes to Picking(Device) with the heard text as filter`() = runTest {
        val session = notUnderstoodSession(scope = backgroundScope)
        session.state.test {
            skipItems(1)
            session.start()
            val not = assertIs<VoiceSessionState.NotUnderstood>(awaitItem())
            session.pickOther()
            val picking = assertIs<VoiceSessionState.Picking>(awaitItem())
            assertEquals(PickStep.Device, picking.step)
            assertEquals("quelque chose salon", picking.heardText)
            assertEquals("quelque chose salon", picking.filter)
            assertNull(picking.pickedId)
            assertEquals(not.suggestions, picking.suggestions)
        }
    }

    @Test fun `onPickDevice goes to Picking(Action, pickedId)`() = runTest {
        val session = notUnderstoodSession(scope = backgroundScope)
        session.state.test {
            skipItems(1)
            session.start()
            skipItems(1)
            session.pickOther()
            skipItems(1)
            session.pickDevice("volet_salon_1")
            val picking = assertIs<VoiceSessionState.Picking>(awaitItem())
            assertEquals(PickStep.Action, picking.step)
            assertEquals("volet_salon_1", picking.pickedId)
        }
    }

    @Test fun `onPickAction runs a control intent through the runner and lands in Done`() = runTest {
        val runner = FakeRunner(resultByEntity = mapOf("volet_salon_1" to CommandResult.Accepted))
        val session = notUnderstoodSession(runner, scope = backgroundScope)
        session.state.test {
            skipItems(1)
            session.start()
            skipItems(1)
            session.pickOther(); skipItems(1)
            session.pickDevice("volet_salon_1"); skipItems(1)
            session.pickAction(VoiceAction.OPEN, remember = false)
            assertIs<VoiceSessionState.Executing>(awaitItem())
            val done = assertIs<VoiceSessionState.Done>(awaitItem())
            assertEquals(listOf("volet_salon_1"), done.result.succeeded)
        }
    }

    @Test fun `onPickAction with QUERY_STATE goes through runner query and lands in Spoken`() = runTest {
        val runner = FakeRunner(queryAnswer = answer)
        val session = notUnderstoodSession(runner, scope = backgroundScope)
        session.state.test {
            skipItems(1)
            session.start()
            skipItems(1)
            session.pickOther(); skipItems(1)
            session.pickDevice("volet_salon_1"); skipItems(1)
            session.pickAction(VoiceAction.QUERY_STATE, remember = false)
            assertIs<VoiceSessionState.Spoken>(awaitItem())
        }
        assertEquals(listOf(VoiceIntent(VoiceAction.QUERY_STATE, listOf("volet_salon_1"))), runner.queried)
    }

    @Test fun `onPickAction with remember=true saves a shortcut mapping heardText to the intent`() = runTest {
        var saved: VoiceShortcut? = null
        val runner = FakeRunner(resultByEntity = mapOf("volet_salon_1" to CommandResult.Accepted))
        val session = notUnderstoodSession(runner, onLearn = { saved = it }, scope = backgroundScope)
        session.state.test {
            skipItems(1)
            session.start()
            skipItems(1)
            session.pickOther(); skipItems(1)
            session.pickDevice("volet_salon_1"); skipItems(1)
            session.pickAction(VoiceAction.OPEN, remember = true)
            skipItems(1)
            assertIs<VoiceSessionState.Done>(awaitItem())
        }
        val s = requireNotNull(saved)
        assertEquals("quelque chose salon", s.phrase)
        assertEquals(VoiceLang.FR, s.lang)
        assertEquals(VoiceIntent(VoiceAction.OPEN, listOf("volet_salon_1")), s.intent)
    }

    @Test fun `onPickAction with remember=false does not save a shortcut`() = runTest {
        var saved: VoiceShortcut? = null
        val runner = FakeRunner(resultByEntity = mapOf("volet_salon_1" to CommandResult.Accepted))
        val session = notUnderstoodSession(runner, onLearn = { saved = it }, scope = backgroundScope)
        session.state.test {
            skipItems(1)
            session.start()
            skipItems(1)
            session.pickOther(); skipItems(1)
            session.pickDevice("volet_salon_1"); skipItems(1)
            session.pickAction(VoiceAction.OPEN, remember = false)
            skipItems(1)
            assertIs<VoiceSessionState.Done>(awaitItem())
        }
        assertNull(saved)
    }

    @Test fun `onPickBack from Action returns to Picking(Device) with same filter and pickedId null`() = runTest {
        val session = notUnderstoodSession(scope = backgroundScope)
        session.state.test {
            skipItems(1)
            session.start()
            skipItems(1)
            session.pickOther(); skipItems(1)
            session.pickFilterChange("volet"); skipItems(1)
            session.pickDevice("volet_salon_1"); skipItems(1)
            session.pickBack()
            val picking = assertIs<VoiceSessionState.Picking>(awaitItem())
            assertEquals(PickStep.Device, picking.step)
            assertEquals("volet", picking.filter)
            assertNull(picking.pickedId)
        }
    }

    @Test fun `onPickBack from Device returns to NotUnderstood with the original suggestions`() = runTest {
        val session = notUnderstoodSession(scope = backgroundScope)
        session.state.test {
            skipItems(1)
            session.start()
            val not = assertIs<VoiceSessionState.NotUnderstood>(awaitItem())
            session.pickOther(); skipItems(1)
            session.pickBack()
            assertEquals(not, awaitItem())
        }
    }

    @Test fun `onPickFilterChange updates filter but keeps the step and pickedId`() = runTest {
        val session = notUnderstoodSession(scope = backgroundScope)
        session.state.test {
            skipItems(1)
            session.start()
            skipItems(1)
            session.pickOther(); skipItems(1)
            session.pickDevice("volet_salon_1"); skipItems(1)
            session.pickFilterChange("alarme")
            val picking = assertIs<VoiceSessionState.Picking>(awaitItem())
            assertEquals("alarme", picking.filter)
            assertEquals(PickStep.Action, picking.step)
            assertEquals("volet_salon_1", picking.pickedId)
        }
    }

    @Test fun `pickAction with DISARM confirm-then-remember saves the shortcut`() = runTest {
        var saved: VoiceShortcut? = null
        val runner = FakeRunner(resultByEntity = mapOf("alarm" to CommandResult.Accepted))
        val session = notUnderstoodSession(runner, onLearn = { saved = it }, scope = backgroundScope)
        session.state.test {
            skipItems(1)
            session.start()
            skipItems(1) // NotUnderstood
            session.pickOther(); skipItems(1)
            session.pickDevice("alarm"); skipItems(1)
            session.pickAction(VoiceAction.DISARM, remember = true)
            val confirm = assertIs<VoiceSessionState.ConfirmNeeded>(awaitItem())
            assertEquals(VoiceAction.DISARM, confirm.intent.action)
            session.provideCode("1234")
            session.confirm(yes = true)
            skipItems(1) // Executing
            assertIs<VoiceSessionState.Done>(awaitItem())
        }
        val s = requireNotNull(saved)
        assertEquals("quelque chose salon", s.phrase)
        assertEquals(VoiceLang.FR, s.lang)
        assertEquals(VoiceIntent(VoiceAction.DISARM, listOf("alarm")), s.intent)
    }

    @Test fun `onPickAction with QUERY_STATE teaches even when the answer is not speakable`() = runTest {
        var saved: VoiceShortcut? = null
        val runner = FakeRunner() // default queryAnswer has speakable=false (no-reading fallback)
        val session = notUnderstoodSession(runner, onLearn = { saved = it }, scope = backgroundScope)
        session.state.test {
            skipItems(1)
            session.start()
            skipItems(1) // NotUnderstood
            session.pickOther(); skipItems(1)
            session.pickDevice("volet_salon_1"); skipItems(1)
            session.pickAction(VoiceAction.QUERY_STATE, remember = true)
            val spoken = assertIs<VoiceSessionState.Spoken>(awaitItem())
            assertFalse(spoken.speakable)
        }
        val s = requireNotNull(saved)
        assertEquals("quelque chose salon", s.phrase)
        assertEquals(VoiceIntent(VoiceAction.QUERY_STATE, listOf("volet_salon_1")), s.intent)
    }

    @Test fun `onRepeatSpoken calls TtsSpeaker speak again`() = runTest {
        val (speaker, engine) = tts()
        val runner = FakeRunner(queryAnswer = answer)
        val session = VoiceSession(catalog(), emptyList(), FakeSpeechCapture().also { it.scheduleFinal("est-ce que les volets du salon sont fermés") }, runner, backgroundScope, tts = speaker)
        session.state.test {
            skipItems(1)
            session.start()
            assertIs<VoiceSessionState.Spoken>(awaitItem())
            session.repeatSpoken()
        }
        assertEquals(listOf("Les volets sont ouverts.", "Les volets sont ouverts."), engine.spoken)
    }
}
