package com.gekkotron.oikos.connection

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
// Robolectric: the wiring logs a failed callback through android.util.Log, which a plain JVM test cannot call.
@RunWith(AndroidJUnit4::class)
class PushWiringTest {
    @Test
    fun `every time the link connects the registrar is told, other states are ignored`(): Unit = runTest(UnconfinedTestDispatcher()) {
        val link = MutableStateFlow<LinkState>(LinkState.Connecting)
        var connects = 0
        PushWiring(link, { connects++ }, backgroundScope).start()
        assertEquals(0, connects)
        link.value = LinkState.Connected(TransportMode.DIRECT)
        assertEquals(1, connects)
        link.value = LinkState.Reconnecting(1)
        link.value = LinkState.Connected(TransportMode.GATEWAY)
        assertEquals(2, connects)
        link.value = LinkState.Offline(LinkState.Offline.PAUSED)
        assertEquals(2, connects)
    }

    @Test
    fun `a failing callback does not stop the wiring`(): Unit = runTest(UnconfinedTestDispatcher()) {
        val link = MutableStateFlow<LinkState>(LinkState.Connecting)
        var connects = 0
        PushWiring(link, { connects++; error("boom") }, backgroundScope).start()
        link.value = LinkState.Connected(TransportMode.DIRECT)
        link.value = LinkState.Reconnecting(1)
        link.value = LinkState.Connected(TransportMode.DIRECT)
        assertEquals(2, connects)
    }
}
