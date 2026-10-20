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
        val weird = testEntity("w", Kind.SENSOR, "sensor", """{"state_topic":"w","value_template":"{{ value | sum }}"}""")
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

    private fun attributed(template: String) = testEntity(
        "a", Kind.SWITCH, "switch",
        """{"state_topic":"s","command_topic":"c","json_attributes_topic":"s/attr","json_attributes_template":${kotlinx.serialization.json.JsonPrimitive(template)}}""",
    )

    @Test
    fun `json attributes template reshapes the payload`() {
        store.setEntities(listOf(attributed("""{{ {"power": value_json.p | int} | tojson }}""")))
        store.onMessage("s/attr", """{"p":43.2,"noise":1}""")
        assertEquals(mapOf("power" to 43L), store.states.value.getValue("a").attributes)
    }

    @Test
    fun `a failing attributes template keeps the previous attributes and reports once`() {
        store.setEntities(listOf(attributed("""{{ {"power": value_json.p * 2} | tojson }}""")))
        store.onMessage("s/attr", """{"p":2}""")
        store.onMessage("s/attr", """{"p":"x"}""")
        store.onMessage("s/attr", """{"p":"y"}""")
        assertEquals(mapOf("power" to 4L), store.states.value.getValue("a").attributes)
        assertEquals(1, diagnostics.count { "attributes" in it.message })
    }

    @Test
    fun `an attributes template that renders a non-object is ignored`() {
        store.setEntities(listOf(attributed("{{ value_json.p }}")))
        store.onMessage("s/attr", """{"p":2}""")
        assertEquals(emptyMap(), store.states.value.getValue("a").attributes)
        assertEquals(1, diagnostics.count { "attributes" in it.message })
    }

    @Test
    fun `messages for unknown topics are ignored`() {
        store.setEntities(listOf(lamp))
        assertEquals(emptySet(), store.onMessage("nobody/listens", "x"))
    }

    private fun stale(id: String) = store.states.value.getValue(id).stale

    @Test
    fun `a restored message makes its entities stale and a live one clears it`() {
        store.setEntities(listOf(temperature, humidity))
        store.onMessage("z/capteur", """{"temperature":21.5,"humidity":48}""", restored = true)
        assertEquals(StateValue.Numeric(21.5), value("t"))
        assertTrue(stale("t"))
        assertTrue(stale("h"))

        store.onMessage("z/capteur", """{"temperature":22.0,"humidity":47}""")
        assertFalse(stale("t"))
        assertFalse(stale("h"))
    }

    @Test
    fun `a live availability message clears stale too`() {
        store.setEntities(listOf(lamp))
        store.onMessage("s", "ON", restored = true)
        assertTrue(stale("l"))
        store.onMessage("s/avail", "online")
        assertFalse(stale("l"))
    }

    @Test
    fun `settle clears every stale state`() {
        store.setEntities(listOf(temperature, lamp))
        store.onMessage("z/capteur", """{"temperature":21.5}""", restored = true)
        store.onMessage("s", "ON", restored = true)
        store.onMessage("s", "OFF") // live: confirmed
        store.settle()
        assertFalse(stale("t"))
        assertFalse(stale("l"))
        // The broker never re-sent the temperature: the cached value is not shown as current.
        assertEquals(StateValue.Unknown, value("t"))
        assertEquals(StateValue.OnOff(false), value("l"))
    }

    @Test
    fun `an entity added later replays a restored payload as stale`() {
        store.setEntities(listOf(temperature))
        store.onMessage("z/capteur", """{"temperature":21.5,"humidity":48}""", restored = true)
        store.setEntities(listOf(temperature, humidity))
        assertEquals(StateValue.Numeric(48.0), value("h"))
        assertTrue(stale("h"))
    }

    @Test
    fun `switching an entity to a light swaps its mapper without changing bindings`() {
        store.setEntities(listOf(lamp))
        store.onMessage("s", "ON")
        assertEquals(StateValue.OnOff(true), value("l"))
        store.setEntities(listOf(lamp.copy(kind = Kind.LIGHT)))
        assertEquals(StateValue.Light(true), value("l"))
        store.setEntities(listOf(lamp))
        assertEquals(StateValue.OnOff(true), value("l"))
    }

    private var time = Instant.parse("2026-10-14T08:00:00Z")
    private val timed = StateStore(now = { time })
    private val door = testEntity("door", Kind.BINARY_SENSOR, "binary_sensor", """{"state_topic":"d"}""")
    private fun changedAt() = timed.states.value.getValue("door").changedAt

    @Test
    fun `a live flip sets changedAt and a repeat keeps it`() {
        timed.setEntities(listOf(door))
        timed.onMessage("d", "OFF")
        assertEquals(null, changedAt()) // the first message: the flip was not seen
        time = time.plusSeconds(60)
        timed.onMessage("d", "ON")
        assertEquals(time, changedAt())
        val flippedAt = time
        time = time.plusSeconds(600)
        timed.onMessage("d", "ON") // a battery report republishes the same state
        assertEquals(flippedAt, changedAt())
        assertEquals(time, timed.states.value.getValue("door").lastUpdate)
    }

    @Test
    fun `cache restores never count as a flip`() {
        timed.setEntities(listOf(door))
        timed.onMessage("d", "OFF", restored = true)
        time = time.plusSeconds(60)
        timed.onMessage("d", "ON") // differs from the cached value, but the change happened while the app was away
        assertEquals(null, changedAt())
        time = time.plusSeconds(60)
        timed.onMessage("d", "OFF")
        assertEquals(time, changedAt())
    }

    @Test
    fun `a value back from unknown after settle is not a flip`() {
        timed.setEntities(listOf(door))
        timed.onMessage("d", "OFF", restored = true)
        timed.settle() // never re-sent: back to Unknown
        timed.onMessage("d", "ON")
        assertEquals(null, changedAt())
    }

    @Test
    fun `confirmPending and revert leave changedAt alone`() {
        timed.setEntities(listOf(lamp))
        timed.onMessage("s", "OFF")
        timed.markPending("l", StateValue.OnOff(true))
        timed.confirmPending("l")
        assertEquals(null, timed.states.value.getValue("l").changedAt)
        timed.markPending("l", StateValue.OnOff(false))
        timed.revert("l")
        assertEquals(null, timed.states.value.getValue("l").changedAt)
    }
}
