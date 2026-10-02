package com.gekkotron.oikos.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlacementTest {
    @Test
    fun `alarm panels and security-class binary sensors can go on Security`() {
        assertTrue(Placement.securityKind(Kind.ALARM_PANEL, null))
        assertTrue(Placement.securityKind(Kind.BINARY_SENSOR, "door"))
        assertFalse(Placement.securityKind(Kind.BINARY_SENSOR, "problem"))
        assertFalse(Placement.securityKind(Kind.SENSOR, "door"))
    }

    @Test
    fun `security-hidden takes a sensor off Security`() {
        assertTrue(Placement.onSecurity(Kind.BINARY_SENSOR, "door", UiOverride()))
        assertFalse(Placement.onSecurity(Kind.BINARY_SENSOR, "door", UiOverride(securityHidden = true)))
    }

    @Test
    fun `an energy role or a Security sensor lives elsewhere`() {
        assertTrue(Placement.elsewhere(Kind.SENSOR, "power", UiOverride(energyRole = EnergyRole.GRID)))
        assertTrue(Placement.elsewhere(Kind.BINARY_SENSOR, "motion", UiOverride()))
        assertFalse(Placement.elsewhere(Kind.BINARY_SENSOR, "motion", UiOverride(securityHidden = true)))
        assertFalse(Placement.elsewhere(Kind.LIGHT, null, UiOverride()))
    }

    @Test
    fun `Home shows what is not hidden and lives nowhere else, unless pinned`() {
        assertTrue(Placement.onHome(Kind.LIGHT, null, UiOverride()))
        assertFalse(Placement.onHome(Kind.LIGHT, null, UiOverride(hidden = true)))
        assertFalse(Placement.onHome(Kind.BINARY_SENSOR, "door", UiOverride()))
        assertTrue(Placement.onHome(Kind.BINARY_SENSOR, "door", UiOverride(homePinned = true)))
        assertFalse(Placement.onHome(Kind.BINARY_SENSOR, "door", UiOverride(homePinned = true, hidden = true)))
    }
}
