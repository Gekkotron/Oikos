package com.gekkotron.oikos.presence

import com.gekkotron.oikos.data.PresenceConfig
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PresenceDecisionTest {
    private val eiffel = 48.858370 to 2.294481

    private fun config(radius: Int = 150, home: Pair<Double, Double>? = eiffel) =
        PresenceConfig(enabled = true, alias = "phone", homeLat = home?.first, homeLng = home?.second, radiusM = radius)

    @Test
    fun `a fix inside the radius is HOME`() {
        val (lat, lng) = eiffel
        assertEquals(PresenceState.HOME, PresenceDecision.stateOf(lat + 0.0005, lng + 0.0005, config(radius = 150)))
    }

    @Test
    fun `a fix outside the radius is AWAY`() {
        val (lat, lng) = eiffel
        assertEquals(PresenceState.AWAY, PresenceDecision.stateOf(lat + 0.01, lng + 0.01, config(radius = 150)))
    }

    @Test
    fun `a config without a home has no decision`() {
        assertNull(PresenceDecision.stateOf(48.8, 2.3, config(home = null)))
    }

    @Test
    fun `the Arc de Triomphe is AWAY from the Eiffel Tower with a 500m radius`() {
        val arc = 48.873792 to 2.295028
        assertEquals(PresenceState.AWAY, PresenceDecision.stateOf(arc.first, arc.second, config(radius = 500)))
    }

    @Test
    fun `being exactly on the home is HOME`() {
        val (lat, lng) = eiffel
        assertEquals(PresenceState.HOME, PresenceDecision.stateOf(lat, lng, config(radius = 50)))
    }
}
