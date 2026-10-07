package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.push.PushTarget
import org.junit.Test
import kotlin.test.assertEquals

class PushTargetsTest {
    @Test
    fun `a direct profile registers on the default topic with its install id`() {
        val state = ProfileState.Ready(ConnectionProfile.Direct(LocalBroker("192.168.1.91")))
        assertEquals(PushTarget.Ready("abc", "oikos-push/tokens/abc"), pushTargetFor(state, "abc"))
    }

    @Test
    fun `the profile's token topic overrides the default`() {
        val state = ProfileState.Ready(ConnectionProfile.Direct(LocalBroker("192.168.1.91"), pushTokenTopic = "fcm-push/tokens/{installId}"))
        assertEquals(PushTarget.Ready("abc", "fcm-push/tokens/abc"), pushTargetFor(state, "abc"))
    }

    @Test
    fun `the demo, no profile and a locked one never register`() {
        assertEquals(PushTarget.Demo, pushTargetFor(ProfileState.Ready(ConnectionProfile.Demo), "abc"))
        for (state in listOf(ProfileState.None, ProfileState.Locked, ProfileState.Loading)) {
            assertEquals(PushTarget.None, pushTargetFor(state, "abc"), state.toString())
        }
    }
}
