package com.gekkotron.oikos.navigation

import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Bindings
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UiOverride
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private object NoBindings : Bindings
private fun entity(
    id: String,
    kind: Kind,
    unit: String? = null,
    ui: UiOverride = UiOverride(),
) = Entity(id, null, id, kind, null, unit, null, Capabilities(), NoBindings, ui = ui)

private fun state(id: String, value: StateValue): Pair<String, EntityState> = id to EntityState(id, value)

class TabStatusTest {
    @Test
    fun `alarm armed when any panel is in an active alarm state`() {
        val panel = entity("alarm", Kind.ALARM_PANEL)
        assertTrue(alarmArmed(mapOf("alarm" to panel), mapOf(state("alarm", StateValue.Alarm(AlarmState.ARMED_AWAY)))))
        assertTrue(alarmArmed(mapOf("alarm" to panel), mapOf(state("alarm", StateValue.Alarm(AlarmState.TRIGGERED)))))
        assertTrue(alarmArmed(mapOf("alarm" to panel), mapOf(state("alarm", StateValue.Alarm(AlarmState.ARMING)))))
    }

    @Test
    fun `alarm not armed when disarmed or no panel`() {
        val panel = entity("alarm", Kind.ALARM_PANEL)
        assertFalse(alarmArmed(mapOf("alarm" to panel), mapOf(state("alarm", StateValue.Alarm(AlarmState.DISARMED)))))
        assertFalse(alarmArmed(emptyMap(), emptyMap()))
        // A non-panel entity's state cannot flip the shield on.
        val lamp = entity("lamp", Kind.LIGHT)
        assertFalse(alarmArmed(mapOf("lamp" to lamp), mapOf(state("lamp", StateValue.Light(true, 255)))))
    }

    @Test
    fun `solar active when a producing panel is over the idle threshold`() {
        val panel = entity("solar", Kind.SENSOR, unit = "W", ui = UiOverride(energyRole = EnergyRole.SOLAR))
        assertTrue(solarActive(mapOf("solar" to panel), mapOf(state("solar", StateValue.Numeric(50.0)))))
    }

    @Test
    fun `solar inactive below idle, when night, or with no solar role`() {
        val panel = entity("solar", Kind.SENSOR, unit = "W", ui = UiOverride(energyRole = EnergyRole.SOLAR))
        assertFalse(solarActive(mapOf("solar" to panel), mapOf(state("solar", StateValue.Numeric(5.0)))))
        assertFalse(solarActive(mapOf("solar" to panel), mapOf(state("solar", StateValue.Numeric(-100.0)))))
        assertFalse(solarActive(emptyMap(), emptyMap()))
        // Without power units (e.g. the sensor is in "kWh"), the factor is null and no watts can be summed.
        val wrongUnit = entity("solar", Kind.SENSOR, unit = "kWh", ui = UiOverride(energyRole = EnergyRole.SOLAR))
        assertFalse(solarActive(mapOf("solar" to wrongUnit), mapOf(state("solar", StateValue.Numeric(200.0)))))
    }

    @Test
    fun `solar sums panels so two tiny inverters still turn the bolt on together`() {
        val a = entity("inv_a", Kind.SENSOR, unit = "W", ui = UiOverride(energyRole = EnergyRole.SOLAR))
        val b = entity("inv_b", Kind.SENSOR, unit = "W", ui = UiOverride(energyRole = EnergyRole.SOLAR))
        assertTrue(
            solarActive(
                mapOf("inv_a" to a, "inv_b" to b),
                mapOf(state("inv_a", StateValue.Numeric(7.0)), state("inv_b", StateValue.Numeric(7.0))),
            ),
        )
    }
}
