package com.gekkotron.oikos.feature.voice

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandFailure
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.Device
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode
import com.gekkotron.oikos.voice.AnswerFormatter
import com.gekkotron.oikos.voice.VoiceAction
import com.gekkotron.oikos.voice.VoiceCatalogBuilder
import com.gekkotron.oikos.voice.VoiceIntent
import com.gekkotron.oikos.voice.VoiceLang
import com.gekkotron.oikos.voice.VoiceShortcut
import com.gekkotron.oikos.voice.VoiceSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VoiceCommandRunnerTest {

    private val lamp = entity("lamp", "Lampe", Kind.LIGHT, "salon")
    private val catalog = VoiceCatalogBuilder.build(VoiceLang.FR, listOf(lamp), listOf(area("salon", "Salon")))
    private val intent = VoiceIntent(VoiceAction.TURN_ON, listOf("lamp"))

    @Test
    fun `the runner uses the source current at run time, not the one captured at construction`() = runTest {
        val stale = RecordingSource(link = LinkState.Offline("dropped"))
        val fresh = RecordingSource(link = LinkState.Connected(TransportMode.DIRECT))
        var current: RecordingSource = stale
        val runner = VoiceCommandRunner { current }

        current = fresh
        val result = runner.run(intent, catalog, source = VoiceSource.RULES)

        assertEquals(listOf("lamp"), result.succeeded)
        assertEquals(0, stale.sent.size)
        assertEquals(1, fresh.sent.size)
    }

    @Test
    fun `an offline source still throws MqttDown`() = runTest {
        val source = RecordingSource(link = LinkState.Offline("test"))
        val runner = VoiceCommandRunner { source }
        assertFailsWith<MqttDownException> { runner.run(intent, catalog, source = VoiceSource.RULES) }
    }

    private val queryIntent = VoiceIntent(VoiceAction.QUERY_STATE, listOf("lamp"))
    private val lampOn = mapOf("lamp" to EntityState(uniqueId = "lamp", value = StateValue.OnOff(true)))

    @Test
    fun `query snapshots the current states and returns the AnswerFormatter output`() = runTest {
        val source = RecordingSource(link = LinkState.Connected(TransportMode.DIRECT), states = lampOn)
        val runner = VoiceCommandRunner { source }

        val result = runner.query(queryIntent, catalog)

        assertEquals(AnswerFormatter.format(catalog.lang, queryIntent, lampOn, catalog), result.answer)
        assertEquals("La lumière du salon est allumée", result.answer.spoken)
    }

    @Test
    fun `query uses the lazily resolved EntitySource and never captures a stale one`() = runTest {
        val first = RecordingSource(link = LinkState.Connected(TransportMode.DIRECT), states = emptyMap())
        val second = RecordingSource(link = LinkState.Connected(TransportMode.DIRECT), states = lampOn)
        val sources = listOf(first, second)
        var calls = 0
        val runner = VoiceCommandRunner { sources[calls++] }

        val a = runner.query(queryIntent, catalog)
        val b = runner.query(queryIntent, catalog)

        assertEquals(2, calls)
        assertEquals(false, a.answer.speakable)
        assertEquals("La lumière du salon est allumée", b.answer.spoken)
    }

    @Test
    fun `query returns an answer when the broker is disconnected but a cached snapshot exists`() = runTest {
        val source = RecordingSource(link = LinkState.Offline("dropped"), states = lampOn)
        val runner = VoiceCommandRunner { source }

        val result = runner.query(queryIntent, catalog)

        assertEquals(AnswerFormatter.format(catalog.lang, queryIntent, lampOn, catalog), result.answer)
    }

    private class RecordingSource(link: LinkState, private val states: Map<String, EntityState> = emptyMap()) : EntitySource {
        override val link = MutableStateFlow(link)
        override val entities = MutableStateFlow<Map<String, Entity>>(emptyMap())
        override val devices = MutableStateFlow<Map<String, Device>>(emptyMap())
        override val areas = MutableStateFlow<List<Area>>(emptyList())
        override val shortcuts = MutableStateFlow<Map<String, VoiceShortcut>>(emptyMap())
        override val commandFailures = MutableSharedFlow<CommandFailure>()
        val sent = mutableListOf<Pair<String, Command>>()
        override fun states() = flowOf(states)
        override suspend fun send(uniqueId: String, command: Command): CommandResult {
            sent += uniqueId to command
            return CommandResult.Accepted
        }
        override suspend fun setOverride(uniqueId: String, override: UiOverride) {}
        override suspend fun setAreas(areas: List<Area>) {}
        override suspend fun saveShortcut(shortcut: VoiceShortcut) {}
        override suspend fun deleteShortcut(id: String) {}
    }
}
