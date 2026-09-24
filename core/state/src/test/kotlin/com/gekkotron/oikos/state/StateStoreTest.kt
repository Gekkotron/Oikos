package com.gekkotron.oikos.state

import com.gekkotron.oikos.model.Diagnostic
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StateStoreTest {
    private val diagnostics = mutableListOf<Diagnostic>()
    private val clock = Instant.parse("2026-09-24T10:00:00Z")
    private val store = StateStore(now = { clock }, diagnostics = { diagnostics += it })

    private val temperature = testEntity(
        "t", Kind.SENSOR, "sensor", """{"state_topic":"z/capteur","value_template":"{{ value_json.temperature }}"}""",
    )
    private val humidity = testEntity(
        "h", Kind.SENSOR, "sensor", """{"state_topic":"z/capteur","value_template":"{{ value_json.humidity }}"}""",
    )
    private val lamp = testEntity(
        "l", Kind.SWITCH, "switch",
        """{"state_topic":"s","command_topic":"c","availability_topic":"s/avail","json_attributes_topic":"s/attr"}""",
    )

    private fun value(id: String) = store.states.value.getValue(id).value

    @Test
    fun `one shared topic updates every bound entity`() {
        store.setEntities(listOf(temperature, humidity))
        val confirmed = store.onMessage("z/capteur", """{"temperature":21.5,"humidity":48}""")
        assertEquals(setOf("t", "h"), confirmed)
        assertEquals(StateValue.Numeric(21.5), value("t"))
        assertEquals(StateValue.Numeric(48.0), value("h"))
        assertEquals(clock, store.states.value.getValue("t").lastUpdate)
    }

    @Test
    fun `subscription topics include state, availability and attributes`() {
        store.setEntities(listOf(temperature, lamp))
        assertEquals(setOf("z/capteur", "s", "s/avail", "s/attr"), store.subscriptionTopics())
    }

    @Test
    fun `availability defaults and transitions`() {
        store.setEntities(listOf(temperature, lamp))
        assertTrue(store.states.value.getValue("t").available)
        assertFalse(store.states.value.getValue("l").available)
        store.onMessage("s/avail", "online")
        assertTrue(store.states.value.getValue("l").available)
        store.onMessage("s/avail", "offline")
        assertFalse(store.states.value.getValue("l").available)
    }

    @Test
    fun `availability mode all needs every topic online`() {
        val z2m = testEntity(
            "z", Kind.SWITCH, "switch",
            """{"state_topic":"z","command_topic":"z/set","availability_mode":"all","availability":[
                {"topic":"bridge/state","value_template":"{{ value_json.state }}"},
                {"topic":"z/availability","value_template":"{{ value_json.state }}"}]}""",
        )
        store.setEntities(listOf(z2m))
        store.onMessage("bridge/state", """{"state":"online"}""")
        assertFalse(store.states.value.getValue("z").available)
        store.onMessage("z/availability", """{"state":"online"}""")
        assertTrue(store.states.value.getValue("z").available)
        // A legacy plain "offline" payload still counts when the template cannot parse it.
        store.onMessage("bridge/state", "offline")
        assertFalse(store.states.value.getValue("z").available)
    }

    @Test
    fun `unsupported template shows Unsupported and is reported once`() {
        val weird = testEntity("w", Kind.SENSOR, "sensor", """{"state_topic":"w","value_template":"{{ value | tojson }}"}""")
        store.setEntities(listOf(weird))
        store.onMessage("w", "1")
        store.onMessage("w", "2")
        assertEquals(StateValue.Unsupported, value("w"))
        assertEquals(1, diagnostics.size)
    }

    // Review Focus 2
    @Test
    fun `non-JSON payload keeps previous state and reports`() {
        store.setEntities(listOf(temperature))
        store.onMessage("z/capteur", """{"temperature":21}""")
        val confirmed = store.onMessage("z/capteur", "online")
        assertEquals(emptySet(), confirmed)
        assertEquals(StateValue.Numeric(21.0), value("t"))
        store.onMessage("z/capteur", "garbage")
        assertEquals(1, diagnostics.size)
    }

    @Test
    fun `pending lifecycle`() {
        store.setEntities(listOf(lamp))
        store.onMessage("s", "OFF")
        store.markPending("l", StateValue.OnOff(true))
        assertTrue(store.isPending("l"))
        assertEquals(StateValue.OnOff(true), value("l"))

        assertTrue(store.revert("l"))
        assertEquals(StateValue.OnOff(false), value("l"))
        assertFalse(store.revert("l"))

        store.markPending("l", StateValue.OnOff(true))
        store.onMessage("s", "ON")
        assertFalse(store.isPending("l"))
        assertEquals(StateValue.OnOff(true), value("l"))

        store.markPending("l", StateValue.OnOff(false))
        store.confirmPending("l")
        assertFalse(store.isPending("l"))
        assertFalse(store.revert("l"))
        assertEquals(StateValue.OnOff(false), value("l"))
    }

    @Test
    fun `setEntities keeps surviving state and drops removed entities`() {
        store.setEntities(listOf(temperature, humidity))
        store.onMessage("z/capteur", """{"temperature":20,"humidity":50}""")
        store.setEntities(listOf(temperature))
        assertEquals(setOf("t"), store.states.value.keys)
        assertEquals(StateValue.Numeric(20.0), value("t"))
    }

    @Test
    fun `a new entity on an already seen topic gets the last payload`() {
        store.setEntities(listOf(temperature))
        store.onMessage("z/capteur", """{"temperature":20,"humidity":50}""")
        store.setEntities(listOf(temperature, humidity))
        assertEquals(StateValue.Numeric(50.0), value("h"))
    }

    @Test
    fun `json attributes are parsed`() {
        store.setEntities(listOf(lamp))
        store.onMessage("s/attr", """{"linkquality":80,"update":"idle"}""")
        assertEquals(mapOf("linkquality" to 80L, "update" to "idle"), store.states.value.getValue("l").attributes)
    }

    @Test
    fun `messages for unknown topics are ignored`() {
        store.setEntities(listOf(lamp))
        assertEquals(emptySet(), store.onMessage("nobody/listens", "x"))
    }
}
