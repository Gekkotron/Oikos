package com.gekkotron.oikos.feature.widget

import com.gekkotron.oikos.feature.widget.model.TileIcon
import com.gekkotron.oikos.feature.widget.model.TileStatus
import com.gekkotron.oikos.feature.widget.model.TileValue
import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Bindings
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.CodeFormat
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TileMapperTest {
    private object NoBindings : Bindings

    private fun entity(id: String, kind: Kind, unit: String? = null, deviceClass: String? = null, caps: Capabilities = Capabilities(), name: String = "Lamp") =
        Entity(id, null, name, kind, deviceClass, unit, null, caps, NoBindings)

    private val t0 = Instant.ofEpochMilli(1_000_000)

    @Test
    fun light_with_brightness_maps_percent() {
        val tile = TileMapper.map(entity("l", Kind.LIGHT), EntityState("l", StateValue.Light(true, 128), lastUpdate = t0), null, historyEnabled = false, complete = true)!!
        assertEquals(TileValue.Light(true, 50), tile.value)
        assertEquals(TileIcon.LIGHT, tile.icon)
        assertEquals(1_000_000L, tile.updatedAt)
    }

    @Test
    fun temperature_sensor_gets_temperature_icon() {
        val tile = TileMapper.map(
            entity("t", Kind.SENSOR, unit = "°C", deviceClass = "temperature"),
            EntityState("t", StateValue.Numeric(21.4), lastUpdate = t0), null, false, true,
        )!!
        assertEquals(TileValue.Measure(21.4, "°C"), tile.value)
        assertEquals(TileIcon.TEMPERATURE, tile.icon)
    }

    @Test
    fun stale_cached_state_keeps_previous_updatedAt() {
        val prev = TileMapper.map(entity("s", Kind.SWITCH), EntityState("s", StateValue.OnOff(true), lastUpdate = t0), null, false, true)
        val tile = TileMapper.map(
            entity("s", Kind.SWITCH),
            EntityState("s", StateValue.OnOff(false), lastUpdate = t0.plusSeconds(60), stale = true), prev, false, true,
        )!!
        assertEquals(1_000_000L, tile.updatedAt)
    }

    @Test
    fun climate_remembers_last_non_off_mode() {
        val caps = Capabilities(hvacModes = listOf("off", "heat", "cool"))
        val on = TileMapper.map(entity("c", Kind.CLIMATE, caps = caps), EntityState("c", StateValue.Climate("cool", 22.0, 21.0), lastUpdate = t0), null, false, true)!!
        val off = TileMapper.map(entity("c", Kind.CLIMATE, caps = caps), EntityState("c", StateValue.Climate("off", 22.0, 21.0), lastUpdate = t0), on, false, true)!!
        assertEquals("cool", off.lastOnMode)
        assertEquals(listOf("off", "heat", "cool"), off.hvacModes)
    }

    @Test
    fun alarm_copies_code_requirements() {
        val caps = Capabilities(alarmModes = setOf(AlarmMode.AWAY), codeDisarmRequired = true, codeFormat = CodeFormat.TEXT)
        val tile = TileMapper.map(
            entity("a", Kind.ALARM_PANEL, caps = caps),
            EntityState("a", StateValue.Alarm(AlarmState.ARMED_AWAY), lastUpdate = t0), null, false, true,
        )!!
        assertTrue(tile.codeDisarmRequired)
        assertEquals(CodeFormat.TEXT, tile.codeFormat)
        assertEquals(setOf(AlarmMode.AWAY), tile.alarmModes)
    }

    @Test
    fun missing_entity_after_complete_discovery_is_removed() {
        val prev = TileMapper.map(entity("s", Kind.SWITCH), EntityState("s", StateValue.OnOff(true), lastUpdate = t0), null, false, true)
        assertEquals(TileStatus.REMOVED, TileMapper.map(null, null, prev, false, complete = true)!!.status)
    }

    @Test
    fun missing_entity_after_incomplete_discovery_keeps_previous() {
        val prev = TileMapper.map(entity("s", Kind.SWITCH), EntityState("s", StateValue.OnOff(true), lastUpdate = t0), null, false, true)
        assertEquals(prev, TileMapper.map(null, null, prev, false, complete = false))
    }

    @Test
    fun never_seen_entity_maps_to_null() {
        assertNull(TileMapper.map(null, null, null, false, complete = true))
    }

    @Test
    fun unknown_state_is_none_and_entity_name_is_used() {
        // The source already folds the override name into Entity.name.
        val tile = TileMapper.map(entity("s", Kind.SWITCH, name = "Kettle"), null, null, false, true)!!
        assertEquals(TileValue.None, tile.value)
        assertEquals("Kettle", tile.name)
    }

    @Test
    fun binary_sensor_is_contact() {
        val tile = TileMapper.map(
            entity("d", Kind.BINARY_SENSOR, deviceClass = "door"),
            EntityState("d", StateValue.OnOff(true), lastUpdate = t0), null, false, true,
        )!!
        assertEquals(TileValue.Contact("door", true), tile.value)
    }

    @Test
    fun history_needs_both_enabled_and_a_ref() {
        // A SWITCH is a non-charted kind, so HistoryRefs finds no ref even when history is enabled.
        val tile = TileMapper.map(entity("sw", Kind.SWITCH), EntityState("sw", StateValue.OnOff(true), lastUpdate = t0), null, historyEnabled = true, complete = true)!!
        assertEquals(false, tile.historyAvailable)
    }

    @Test
    fun a_partial_discovery_never_drops_a_code_requirement() {
        // The cached alarm config has its code redacted, so before the live config replays the entity says "no code".
        val live = Capabilities(alarmModes = setOf(AlarmMode.AWAY), codeArmRequired = true, codeDisarmRequired = true, codeFormat = CodeFormat.TEXT)
        val state = EntityState("a", StateValue.Alarm(AlarmState.ARMED_AWAY), lastUpdate = t0)
        val prev = TileMapper.map(entity("a", Kind.ALARM_PANEL, caps = live), state, null, false, complete = true)
        val cachedOnly = entity("a", Kind.ALARM_PANEL, caps = Capabilities(alarmModes = setOf(AlarmMode.AWAY)))
        val partial = TileMapper.map(cachedOnly, state, prev, false, complete = false)!!
        assertTrue(partial.codeArmRequired)
        assertTrue(partial.codeDisarmRequired)
        assertEquals(CodeFormat.TEXT, partial.codeFormat)
        // Once discovery settled, the live config is the truth.
        val settled = TileMapper.map(cachedOnly, state, prev, false, complete = true)!!
        assertEquals(false, settled.codeDisarmRequired)
    }
}
