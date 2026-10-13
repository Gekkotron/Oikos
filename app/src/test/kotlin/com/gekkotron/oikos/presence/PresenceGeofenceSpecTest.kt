package com.gekkotron.oikos.presence

import com.gekkotron.oikos.data.PresenceConfig
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PresenceGeofenceSpecTest {
    @Test
    fun `a disabled config asks for no geofence`() {
        assertNull(PresenceGeofenceSpec.from(PresenceConfig(enabled = false, alias = "a", homeLat = 1.0, homeLng = 2.0, radiusM = 150)))
    }

    @Test
    fun `a config without a home asks for no geofence`() {
        assertNull(PresenceGeofenceSpec.from(PresenceConfig(enabled = true, alias = "a", homeLat = null, homeLng = 2.0, radiusM = 150)))
        assertNull(PresenceGeofenceSpec.from(PresenceConfig(enabled = true, alias = "a", homeLat = 1.0, homeLng = null, radiusM = 150)))
    }

    @Test
    fun `an enabled config with a home gives one spec`() {
        val spec = PresenceGeofenceSpec.from(PresenceConfig(enabled = true, alias = "pixel", homeLat = 48.858, homeLng = 2.294, radiusM = 150))!!
        assertEquals(PresenceGeofenceSpec.REQUEST_ID, spec.requestId)
        assertEquals(48.858, spec.lat)
        assertEquals(2.294, spec.lng)
        assertEquals(150, spec.radiusM)
    }
}
