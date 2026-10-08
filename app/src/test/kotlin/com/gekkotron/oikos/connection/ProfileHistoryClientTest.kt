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

class ProfileHistoryClientTest {
    private val states = MutableStateFlow<ProfileState>(ProfileState.Loading)
    private val direct = ConnectionProfile.Direct(LocalBroker("192.168.1.91"))
    private val urls = mutableListOf<String>()

    private fun kotlinx.coroutines.test.TestScope.client() = ProfileHistoryClient(
        states, get = { urls += it; HttpGetResult.Response(200, """{"window":600,"points":[]}""".encodeToByteArray(), null) }, scope = backgroundScope,
    )

    @Test
    fun `available only with a history url or the demo`() = runTest {
        val c = client()
        states.value = ProfileState.Ready(direct)
        assertEquals(false, c.available.first { !it })
        states.value = ProfileState.Ready(direct.copy(historyUrl = "http://192.168.1.91:8090"))
        c.available.first { it }
        states.value = ProfileState.Ready(ConnectionProfile.Demo)
        c.available.first { it }
        states.value = ProfileState.None
        c.available.first { !it }
    }

    @Test
    fun `direct asks the service, the demo never does`() = runTest {
        val c = client()
        states.value = ProfileState.Ready(direct.copy(historyUrl = "http://192.168.1.91:8090"))
        assertIs<HistoryResult.Ok<*>>(c.series(HistoryRef("t", "k"), HistoryRange.H24))
        assertEquals(1, urls.size)
        states.value = ProfileState.Ready(ConnectionProfile.Demo)
        assertIs<HistoryResult.Ok<*>>(c.series(HistoryRef("t", "k"), HistoryRange.D7))
        assertEquals(1, urls.size)
    }
}
