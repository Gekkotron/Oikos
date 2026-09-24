package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals

class CardFormatterTest {
    private val sensor = testEntity("t", Kind.SENSOR, unit = "°C", deviceClass = "temperature")

    @Test
    fun `unknown and unsupported states render as None`() {
        assertEquals(CardValue.None, CardFormatter.format(sensor, StateValue.Unknown, Locale.US))
        assertEquals(CardValue.None, CardFormatter.format(sensor, StateValue.Unsupported, Locale.US))
    }

    @Test
    fun `numbers follow the locale with at most one decimal`() {
        assertEquals(CardValue.Measurement("21.5", "°C"), CardFormatter.format(sensor, StateValue.Numeric(21.54), Locale.US))
        assertEquals(CardValue.Measurement("21,5", "°C"), CardFormatter.format(sensor, StateValue.Numeric(21.5), Locale.FRANCE))
        assertEquals("2,140", CardFormatter.number(2140.0, Locale.US))
        assertEquals("19", CardFormatter.number(19.0, Locale.US))
    }

    @Test
    fun `lights show brightness percent only when on`() {
        val light = testEntity("l", Kind.LIGHT)
        assertEquals(CardValue.Brightness(71), CardFormatter.format(light, StateValue.Light(true, 180), Locale.US))
        assertEquals(CardValue.OnOff(false), CardFormatter.format(light, StateValue.Light(false, 180), Locale.US))
        assertEquals(CardValue.OnOff(true), CardFormatter.format(light, StateValue.Light(true, null), Locale.US))
    }

    @Test
    fun `binary sensors become Contact with their device class`() {
        val door = testEntity("d", Kind.BINARY_SENSOR, deviceClass = "door")
        assertEquals(CardValue.Contact("door", true), CardFormatter.format(door, StateValue.OnOff(true), Locale.US))
        val sw = testEntity("s", Kind.SWITCH)
        assertEquals(CardValue.OnOff(true), CardFormatter.format(sw, StateValue.OnOff(true), Locale.US))
    }

    @Test
    fun `cover, climate and alarm pass through`() {
        assertEquals(CardValue.Cover(CoverState.OPEN, 60), CardFormatter.format(sensor, StateValue.Cover(CoverState.OPEN, 60), Locale.US))
        assertEquals(
            CardValue.Climate("22.4", "21", "heat"),
            CardFormatter.format(sensor, StateValue.Climate("heat", 21.0, 22.4), Locale.US),
        )
        assertEquals(CardValue.Alarm(AlarmState.DISARMED), CardFormatter.format(sensor, StateValue.Alarm(AlarmState.DISARMED), Locale.US))
    }
}
