package com.gekkotron.oikos.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SwitchTypeTest {
    @Test
    fun `of returns null for non-switch kinds`() {
        assertNull(SwitchType.of(Kind.LIGHT, UiOverride(showAs = "light")))
        assertNull(SwitchType.of(Kind.COVER, UiOverride()))
        assertNull(SwitchType.of(Kind.SENSOR, UiOverride(showAs = "door")))
    }

    @Test
    fun `of maps every known value`() {
        assertEquals(SwitchType.SWITCH, SwitchType.of(Kind.SWITCH, UiOverride()))
        assertEquals(SwitchType.LIGHT, SwitchType.of(Kind.SWITCH, UiOverride(showAs = "light")))
        assertEquals(SwitchType.DOOR, SwitchType.of(Kind.SWITCH, UiOverride(showAs = "door")))
        assertEquals(SwitchType.SIREN, SwitchType.of(Kind.SWITCH, UiOverride(showAs = "siren")))
        assertEquals(SwitchType.CHIME, SwitchType.of(Kind.SWITCH, UiOverride(showAs = "chime")))
        assertEquals(SwitchType.PLUG, SwitchType.of(Kind.SWITCH, UiOverride(showAs = "plug")))
        assertEquals(SwitchType.SWITCH, SwitchType.of(Kind.SWITCH, UiOverride(showAs = "fan")))
    }

    @Test
    fun `effective kind promotes only the light type`() {
        assertEquals(Kind.LIGHT, effectiveKind(Kind.SWITCH, UiOverride(showAs = "light")))
        assertEquals(Kind.SWITCH, effectiveKind(Kind.SWITCH, UiOverride(showAs = "door")))
        assertEquals(Kind.SWITCH, effectiveKind(Kind.SWITCH, UiOverride(showAs = "siren")))
        assertEquals(Kind.SWITCH, effectiveKind(Kind.SWITCH, UiOverride(showAs = "chime")))
        assertEquals(Kind.SWITCH, effectiveKind(Kind.SWITCH, UiOverride(showAs = "plug")))
        assertEquals(Kind.SWITCH, effectiveKind(Kind.SWITCH, UiOverride()))
        assertEquals(Kind.COVER, effectiveKind(Kind.COVER, UiOverride(showAs = "light")))
    }

    @Test
    fun `keys enumerate the known types`() {
        assertEquals(listOf(null, "light", "door", "siren", "chime", "plug"), SwitchType.entries.map { it.key })
    }

    @Test
    fun `SHOW_AS matches the enum keys`() {
        assertEquals(SwitchType.entries.mapNotNull { it.key }, SHOW_AS)
    }
}
