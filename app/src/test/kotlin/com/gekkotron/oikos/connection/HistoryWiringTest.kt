package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.data.ProfileState
import org.junit.Test
import kotlin.test.assertEquals

class HistoryWiringTest {
    @Test
    fun `a direct profile subscribes to the history topic`() {
        assertEquals(setOf("oikos/push/history"), historyFilters(ProfileState.Ready(ConnectionProfile.Direct(LocalBroker("192.168.1.91")))))
    }

    @Test
    fun `the demo, no profile and a locked one subscribe to nothing`() {
        for (state in listOf(ProfileState.Ready(ConnectionProfile.Demo), ProfileState.None, ProfileState.Locked, ProfileState.Loading)) {
            assertEquals(emptySet(), historyFilters(state), state.toString())
        }
    }
}
