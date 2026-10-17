package com.gekkotron.oikos.feature.voice

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandFailure
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.Device
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode
import com.gekkotron.oikos.voice.VoiceShortcut
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceReadinessTest {

    @Test
    fun `an offline source times out and returns false`() = runTest {
        val source = FakeSource(link = LinkState.Offline("test"))
        val result = awaitVoiceSourceReady(source, connectTimeout = 500.milliseconds, settleTimeout = 100.milliseconds)
        assertFalse(result)
    }

    @Test
    fun `an already-connected source with entities returns true immediately`() = runTest {
        val source = FakeSource(
            link = LinkState.Connected(TransportMode.DIRECT),
            entities = mapOf("lamp" to entity("lamp", "Lampe", Kind.LIGHT, "salon")),
        )
        val result = awaitVoiceSourceReady(source, connectTimeout = 5.seconds, settleTimeout = 2.seconds)
        assertTrue(result)
    }

    @Test
    fun `a late connection followed by late entities still returns true`() = runTest {
        val source = FakeSource(link = LinkState.Offline("test"))
        var result: Boolean? = null
        val job = launch { result = awaitVoiceSourceReady(source, connectTimeout = 5.seconds, settleTimeout = 2.seconds) }
        advanceTimeBy(1.seconds); runCurrent()
        assertNull(result)
        source.link.value = LinkState.Connected(TransportMode.DIRECT)
        runCurrent()
        assertNull(result)
        source.entities.value = mapOf("lamp" to entity("lamp", "Lampe", Kind.LIGHT, "salon"))
        job.join()
        assertEquals(true, result)
    }

    @Test
    fun `a connected but empty source returns true after the settle window`() = runTest {
        val source = FakeSource(link = LinkState.Connected(TransportMode.DIRECT))
        var result: Boolean? = null
        val job = launch { result = awaitVoiceSourceReady(source, connectTimeout = 5.seconds, settleTimeout = 500.milliseconds) }
        advanceTimeBy(400.milliseconds); runCurrent()
        assertNull(result)
        job.join()
        assertEquals(true, result) // an empty house is still a ready house
    }

    private class FakeSource(
        link: LinkState = LinkState.Offline("init"),
        entities: Map<String, Entity> = emptyMap(),
    ) : EntitySource {
        override val link = MutableStateFlow(link)
        override val entities = MutableStateFlow(entities)
        override val devices = MutableStateFlow<Map<String, Device>>(emptyMap())
        override val areas = MutableStateFlow<List<Area>>(emptyList())
        override val shortcuts = MutableStateFlow<Map<String, VoiceShortcut>>(emptyMap())
        override val commandFailures = MutableSharedFlow<CommandFailure>()
        override fun states() = MutableStateFlow<Map<String, EntityState>>(emptyMap())
        override suspend fun send(uniqueId: String, command: Command): CommandResult = CommandResult.Accepted
        override suspend fun setOverride(uniqueId: String, override: UiOverride) {}
        override suspend fun setAreas(areas: List<Area>) {}
        override suspend fun saveShortcut(shortcut: VoiceShortcut) {}
        override suspend fun deleteShortcut(id: String) {}
    }
}
