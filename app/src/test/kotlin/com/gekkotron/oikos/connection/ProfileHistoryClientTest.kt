package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.history.HistoryRange
import com.gekkotron.oikos.history.HistoryRef
import com.gekkotron.oikos.history.HistoryResult
import com.gekkotron.oikos.transport.HttpGetResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProfileHistoryClientTest {
    private val states = MutableStateFlow<ProfileState>(ProfileState.Loading)
    private val direct = ConnectionProfile.Direct(LocalBroker("192.168.1.91"))
    private val urls = mutableListOf<String>()

    private fun kotlinx.coroutines.test.TestScope.client() = ProfileHistoryClient(
        states, get = { urls += it; HttpGetResult.Response(200, """{"window":600,"points":[]}""".encodeToByteArray(), null) }, scope = backgroundScope,
    )

    @Test
    fun `available for any direct profile and the demo, not without a profile`() = runTest {
        val c = client()
        assertEquals(false, c.available.value)
        states.value = ProfileState.Ready(direct)
        assertEquals(true, c.available.first { it })
        states.value = ProfileState.None
        assertEquals(false, c.available.first { !it })
        states.value = ProfileState.Ready(ConnectionProfile.Demo)
        assertEquals(true, c.available.first { it })
        states.value = ProfileState.None
        assertEquals(false, c.available.first { !it })
        states.value = ProfileState.Ready(direct.copy(historyUrl = "http://nas:9000"))
        assertEquals(true, c.available.first { it })
    }

    @Test
    fun `direct without an override asks the service next to the broker`() = runTest {
        val c = client()
        states.value = ProfileState.Ready(direct)
        assertIs<HistoryResult.Ok<*>>(c.series(HistoryRef("t"), HistoryRange.H24))
        assertEquals(1, urls.size)
        assertTrue(urls.single().startsWith("http://192.168.1.91:8081/api/v1/history/"), urls.single())
    }

    @Test
    fun `direct with an override asks the override, the demo never asks`() = runTest {
        val c = client()
        states.value = ProfileState.Ready(direct.copy(historyUrl = "http://nas:9000"))
        assertIs<HistoryResult.Ok<*>>(c.series(HistoryRef("t"), HistoryRange.H24))
        assertEquals(1, urls.size)
        assertTrue(urls.single().startsWith("http://nas:9000/api/v1/history/"), urls.single())
        states.value = ProfileState.Ready(ConnectionProfile.Demo)
        assertIs<HistoryResult.Ok<*>>(c.series(HistoryRef("t"), HistoryRange.D7))
        assertEquals(1, urls.size)
    }

    @Test
    fun `lastChanges goes to the service for a direct profile`() = runTest {
        val c = client()
        states.value = ProfileState.Ready(direct)
        c.lastChanges(listOf(HistoryRef("t")))
        assertEquals("http://192.168.1.91:8081/api/v1/history/last_change?entity=t", urls.single())
    }
}
