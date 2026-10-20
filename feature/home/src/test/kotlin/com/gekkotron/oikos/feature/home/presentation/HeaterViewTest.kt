package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.StateValue
import kotlin.test.Test
import kotlin.test.assertEquals

/** Ports Oikos-os `crates/oikos-web/src/cards/water_heater.rs` tests one for one, plus the app's own edges. */
class HeaterViewTest {
    private fun of(mode: String?, attributes: Map<String, Any?>) =
        HeaterView.of(EntityState("wh", StateValue.Climate(mode, null, 58.5), attributes))

    @Test
    fun `power above zero is heating`() =
        assertEquals(HeaterView(true, 43, 17.9, HeaterChip.Heating(43)), of("heat", mapOf("inlet_temperature" to 17.9, "power" to 43L)))

    @Test
    fun `power zero is idle even in heat mode`() =
        assertEquals(HeaterView(false, 0, null, HeaterChip.Idle), of("heat", mapOf("power" to 0L)))

    @Test
    fun `without power the mode decides`() =
        assertEquals(HeaterView(true, null, null, HeaterChip.Mode("eco")), of("eco", emptyMap()))

    @Test
    fun `mode off without power is off`() =
        assertEquals(HeaterView(false, null, null, HeaterChip.Off), of("OFF", emptyMap()))

    @Test
    fun `no mode and no power has no chip`() =
        assertEquals(HeaterView(false, null, null, null), of(null, emptyMap()))

    @Test
    fun `non-numeric attributes are ignored`() =
        assertEquals(HeaterView(false, null, null, HeaterChip.Off), of("off", mapOf("power" to "43", "inlet_temperature" to null)))

    @Test
    fun `inlet is optional and accepts an integer`() {
        assertEquals(12.0, of(null, mapOf("inlet_temperature" to 12L)).inlet)
        assertEquals(null, of(null, mapOf("power" to 10L)).inlet)
    }

    @Test
    fun `power is clamped to a percentage`() {
        assertEquals(100, of(null, mapOf("power" to 140.4)).power)
        assertEquals(0, of(null, mapOf("power" to -5L)).power)
        assertEquals(false, of(null, mapOf("power" to -5L)).heating)
    }

    @Test
    fun `power rounds half away from zero`() = assertEquals(43, of(null, mapOf("power" to 42.5)).power)

    @Test
    fun `non-finite numbers are ignored`() {
        assertEquals(null, of(null, mapOf("power" to Double.NaN)).power)
        assertEquals(null, of(null, mapOf("inlet_temperature" to Double.POSITIVE_INFINITY)).inlet)
    }

    @Test
    fun `no state has no chip`() = assertEquals(HeaterView(false, null, null, null), HeaterView.of(null))
}
