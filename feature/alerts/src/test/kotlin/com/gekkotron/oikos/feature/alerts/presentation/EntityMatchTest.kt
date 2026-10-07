package com.gekkotron.oikos.feature.alerts.presentation

import com.gekkotron.oikos.model.Bindings
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EntityMatchTest {
    private object NoBindings : Bindings

    private fun entity(id: String, name: String = id) =
        Entity(id, null, name, Kind.BINARY_SENSOR, "door", null, null, Capabilities(), NoBindings)

    private val entities = listOf(entity("front_door", "Front door"), entity("0xa4c138", "Garage sensor")).associateBy { it.uniqueId }

    @Test
    fun `the exact unique id matches`() {
        assertEquals("0xa4c138", EntityMatch.find("0xa4c138", entities))
    }

    @Test
    fun `an entity_id matches on its object id`() {
        assertEquals("front_door", EntityMatch.find("binary_sensor.front_door", entities))
    }

    @Test
    fun `an entity_id matches the entity's name slug`() {
        assertEquals("0xa4c138", EntityMatch.find("binary_sensor.garage_sensor", entities))
    }

    @Test
    fun `unknown, blank and null match nothing`() {
        assertNull(EntityMatch.find("binary_sensor.window", entities))
        assertNull(EntityMatch.find("  ", entities))
        assertNull(EntityMatch.find(null, entities))
        assertNull(EntityMatch.find("binary_sensor.front_door", emptyMap()))
    }
}
