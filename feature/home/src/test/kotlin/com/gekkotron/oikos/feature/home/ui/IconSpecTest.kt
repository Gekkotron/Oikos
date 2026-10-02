package com.gekkotron.oikos.feature.home.ui

import com.gekkotron.oikos.designsystem.icons.OikosGlyph
import com.gekkotron.oikos.feature.home.presentation.HomeUiBuilder
import com.gekkotron.oikos.feature.home.presentation.RoomFilter
import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.transport.LinkState
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IconSpecTest {
    private fun spec(kind: Kind, value: StateValue?, deviceClass: String? = null, available: Boolean = true): IconSpec {
        // Pinned: a door or motion sensor would otherwise only be on Security.
        val e = testEntity("e", kind, deviceClass = deviceClass, ui = UiOverride(homePinned = true))
        val states = value?.let { mapOf("e" to EntityState("e", it, available = available)) } ?: emptyMap()
        val card = HomeUiBuilder.build(mapOf("e" to e), states, emptyList(), RoomFilter.All, LinkState.Connecting, Locale.US).card("e")!!
        return iconSpec(card)
    }

    @Test
    fun `a shutter targets its position, clamped, whatever its state`() {
        assertEquals(IconSpec.Shutter(0.6f, null), spec(Kind.COVER, StateValue.Cover(CoverState.OPEN, 60)))
        assertEquals(IconSpec.Shutter(0.3f, null), spec(Kind.COVER, StateValue.Cover(CoverState.STOPPED, 30)))
        assertEquals(IconSpec.Shutter(0.3f, null), spec(Kind.COVER, StateValue.Cover(CoverState.UNKNOWN, 30)))
        assertEquals(IconSpec.Shutter(1f, null), spec(Kind.COVER, StateValue.Cover(CoverState.OPEN, 101)))
        assertEquals(IconSpec.Shutter(0f, null), spec(Kind.COVER, StateValue.Cover(CoverState.CLOSED, -1)))
    }

    @Test
    fun `a shutter without position is fully open or closed, else keeps its drawing`() {
        assertEquals(IconSpec.Shutter(1f, null), spec(Kind.COVER, StateValue.Cover(CoverState.OPEN)))
        assertEquals(IconSpec.Shutter(0f, null), spec(Kind.COVER, StateValue.Cover(CoverState.CLOSED)))
        assertEquals(IconSpec.Shutter(null, null), spec(Kind.COVER, StateValue.Cover(CoverState.STOPPED)))
        assertEquals(IconSpec.Shutter(null, null), spec(Kind.COVER, null))
    }

    @Test
    fun `a moving shutter carries its direction`() {
        assertEquals(IconSpec.Shutter(0.4f, CoverState.OPENING), spec(Kind.COVER, StateValue.Cover(CoverState.OPENING, 40)))
        assertEquals(IconSpec.Shutter(null, CoverState.CLOSING), spec(Kind.COVER, StateValue.Cover(CoverState.CLOSING)))
    }

    @Test
    fun `doors, windows and garage doors swing with their contact`() {
        assertEquals(IconSpec.Swing(SwingShape.DOOR, true), spec(Kind.BINARY_SENSOR, StateValue.OnOff(true), "door"))
        assertEquals(IconSpec.Swing(SwingShape.DOOR, false), spec(Kind.BINARY_SENSOR, StateValue.OnOff(false), "opening"))
        assertEquals(IconSpec.Swing(SwingShape.WINDOW, true), spec(Kind.BINARY_SENSOR, StateValue.OnOff(true), "window"))
        assertEquals(IconSpec.Swing(SwingShape.GARAGE, false), spec(Kind.BINARY_SENSOR, StateValue.OnOff(false), "garage_door"))
    }

    @Test
    fun `an unknown or unavailable contact is neutral and not an open contact`() {
        assertEquals(IconSpec.Swing(SwingShape.DOOR, null), spec(Kind.BINARY_SENSOR, null, "door"))
        val unavailable = spec(Kind.BINARY_SENSOR, StateValue.OnOff(true), "door", available = false)
        assertEquals(IconSpec.Swing(SwingShape.DOOR, null), unavailable)
        assertFalse(unavailable.openContact)
        assertTrue(spec(Kind.BINARY_SENSOR, StateValue.OnOff(true), "window").openContact)
    }

    @Test
    fun `other kinds get their static glyph, lit only for lights and switches that are on`() {
        assertEquals(IconSpec.Static(OikosGlyph.BULB, true), spec(Kind.LIGHT, StateValue.Light(true, 180)))
        assertEquals(IconSpec.Static(OikosGlyph.PLUG, false), spec(Kind.SWITCH, StateValue.OnOff(false)))
        assertEquals(IconSpec.Static(OikosGlyph.THERMOMETER, false), spec(Kind.SENSOR, StateValue.Numeric(20.0), "temperature"))
        assertEquals(IconSpec.Static(OikosGlyph.DROP, false), spec(Kind.SENSOR, StateValue.Numeric(50.0), "humidity"))
        assertEquals(IconSpec.Static(OikosGlyph.BOLT, false), spec(Kind.SENSOR, StateValue.Numeric(5.0), "energy"))
        assertEquals(IconSpec.Static(OikosGlyph.TREND, false), spec(Kind.SENSOR, StateValue.Numeric(5.0), "pm25"))
        assertEquals(IconSpec.Static(OikosGlyph.WALKER, false), spec(Kind.BINARY_SENSOR, StateValue.OnOff(true), "motion"))
        assertEquals(IconSpec.Static(OikosGlyph.FLAME, false), spec(Kind.BINARY_SENSOR, StateValue.OnOff(false), "smoke"))
        assertEquals(IconSpec.Static(OikosGlyph.DOT, false), spec(Kind.BINARY_SENSOR, StateValue.OnOff(false), "vibration"))
        assertEquals(IconSpec.Static(OikosGlyph.SNOWFLAKE, false), spec(Kind.CLIMATE, null))
        assertEquals(IconSpec.Static(OikosGlyph.SHIELD, false), spec(Kind.ALARM_PANEL, null))
        assertEquals(IconSpec.Static(OikosGlyph.SLIDER, false), spec(Kind.NUMBER, null))
        assertEquals(IconSpec.Static(OikosGlyph.LIST, false), spec(Kind.SELECT, null))
        assertEquals(IconSpec.Static(OikosGlyph.PLAY, false), spec(Kind.BUTTON, null))
    }

    @Test
    fun `an unavailable shutter keeps its last position but stops moving`() {
        assertEquals(IconSpec.Shutter(0.4f, null), spec(Kind.COVER, StateValue.Cover(CoverState.CLOSING, 40), available = false))
        assertEquals(IconSpec.Shutter(null, null), spec(Kind.COVER, StateValue.Cover(CoverState.OPENING), available = false))
    }
}
