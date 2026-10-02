package com.gekkotron.oikos.feature.energy.presentation

import com.gekkotron.oikos.feature.energy.energyEntity
import com.gekkotron.oikos.feature.energy.numeric
import com.gekkotron.oikos.feature.energy.unknown
import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.transport.LinkState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnergyFlowBuilderTest {
    private fun build(
        entities: List<Entity>,
        states: List<EntityState>,
        now: Long = 0,
        previous: ConsumerOrder? = null,
    ) = EnergyFlowBuilder.build(entities.associateBy { it.uniqueId }, states.associateBy { it.uniqueId }, LinkState.Connecting, now, previous)

    /** This house (spec §1.1): signed grid, solar, two consumers, derived house. */
    private val house = listOf(
        energyEntity("grid", EnergyRole.GRID),
        energyEntity("solar", EnergyRole.SOLAR),
        energyEntity("ac", EnergyRole.CONSUMER, name = "AC"),
        energyEntity("heater", EnergyRole.CONSUMER, name = "Water heater"),
    )
    private val houseStates = listOf(numeric("grid", -400.0), numeric("solar", 3200.0), numeric("ac", 600.0), numeric("heater", 1700.0))

    @Test
    fun `nothing discovered is loading`() {
        val s = build(emptyList(), emptyList())
        assertTrue(s.loading)
        assertEquals(false, s.empty)
    }

    @Test
    fun `no tagged entity is empty`() {
        val s = build(listOf(energyEntity("t", role = null)), emptyList())
        assertEquals(false, s.loading)
        assertTrue(s.empty)
    }

    @Test
    fun `this house derives the house and self consumption`() {
        val s = build(house, houseStates)
        assertEquals(2800.0, s.node("house")!!.value)
        assertEquals(400.0, s.node("grid")!!.value)
        assertEquals(Flow.OUT, s.node("grid")!!.flow)
        assertEquals(Flow.IN, s.node("solar")!!.flow)
        assertEquals(87, s.selfConsumption)
        assertNull(s.hint)
        assertEquals(listOf("solar", "grid", "house", "heater", "ac"), s.diagram.map { it.id })
    }

    @Test
    fun `measured house wins over the derived one`() {
        val s = build(house + energyEntity("load", EnergyRole.HOUSE), houseStates + numeric("load", 2500.0))
        assertEquals(2500.0, s.node("house")!!.value)
    }

    @Test
    fun `invert flips a grid meter`() {
        val s = build(listOf(energyEntity("grid", EnergyRole.GRID, invert = true)), listOf(numeric("grid", 400.0)))
        assertEquals(Flow.OUT, s.node("grid")!!.flow)
    }

    @Test
    fun `invert is ignored on solar`() {
        val s = build(listOf(energyEntity("solar", EnergyRole.SOLAR, invert = true)), listOf(numeric("solar", 1000.0)))
        assertEquals(1000.0, s.node("solar")!!.value)
    }

    @Test
    fun `grid export is subtracted from grid import`() {
        val s = build(
            listOf(energyEntity("imp", EnergyRole.GRID), energyEntity("exp", EnergyRole.GRID_EXPORT), energyEntity("solar", EnergyRole.SOLAR)),
            listOf(numeric("imp", 0.0), numeric("exp", 700.0), numeric("solar", 2000.0)),
        )
        assertEquals(700.0, s.node("grid")!!.value)
        assertEquals(Flow.OUT, s.node("grid")!!.flow)
        assertEquals(1300.0, s.node("house")!!.value)
    }

    @Test
    fun `battery discharge feeds the house and charge leaves it`() {
        val discharge = build(listOf(energyEntity("bat", EnergyRole.BATTERY), energyEntity("grid", EnergyRole.GRID)), listOf(numeric("bat", 500.0), numeric("grid", 100.0)))
        assertEquals(Flow.IN, discharge.node("battery")!!.flow)
        assertEquals(600.0, discharge.node("house")!!.value)
        val charge = build(listOf(energyEntity("bat", EnergyRole.BATTERY), energyEntity("grid", EnergyRole.GRID)), listOf(numeric("bat", -500.0), numeric("grid", 900.0)))
        assertEquals(Flow.OUT, charge.node("battery")!!.flow)
        assertEquals(400.0, charge.node("house")!!.value)
    }

    @Test
    fun `units are converted to watts`() {
        val s = build(listOf(energyEntity("solar", EnergyRole.SOLAR, unit = "kW")), listOf(numeric("solar", 3.2)))
        assertEquals(3200.0, s.node("solar")!!.value)
    }

    @Test
    fun `a bad unit makes the role unknown and the derived house unknown`() {
        val s = build(listOf(energyEntity("solar", EnergyRole.SOLAR, unit = "kWh"), energyEntity("grid", EnergyRole.GRID)), listOf(numeric("solar", 3.2), numeric("grid", 100.0)))
        assertNull(s.node("solar")!!.value)
        assertNull(s.node("house")!!.value)
    }

    @Test
    fun `two entities on one role are summed`() {
        val s = build(listOf(energyEntity("s1", EnergyRole.SOLAR), energyEntity("s2", EnergyRole.SOLAR)), listOf(numeric("s1", 1000.0), numeric("s2", 500.0)))
        assertEquals(1500.0, s.node("solar")!!.value)
        assertEquals(1, s.diagram.count { it.kind == NodeKind.SOLAR })
    }

    @Test
    fun `negative derived house shows zero and the sign hint`() {
        val s = build(listOf(energyEntity("grid", EnergyRole.GRID), energyEntity("solar", EnergyRole.SOLAR)), listOf(numeric("grid", -3000.0), numeric("solar", 1000.0)))
        assertEquals(0.0, s.node("house")!!.value)
        assertEquals(EnergyHint.CHECK_SIGN, s.hint)
    }

    @Test
    fun `all unknown shows dashes without hint`() {
        val s = build(house, house.map { unknown(it.uniqueId) })
        assertTrue(s.diagram.all { it.value == null })
        assertTrue(s.diagram.all { it.flow == Flow.IDLE })
        assertNull(s.hint)
        assertNull(s.selfConsumption)
    }

    @Test
    fun `night hides self consumption`() {
        val s = build(house, listOf(numeric("grid", 900.0), numeric("solar", 0.0), numeric("ac", 300.0), numeric("heater", 0.0)))
        assertEquals(900.0, s.node("house")!!.value)
        assertNull(s.selfConsumption)
        assertEquals(Flow.IDLE, s.node("solar")!!.flow)
    }

    @Test
    fun `self consumption needs at least 50 W of solar`() {
        val s = build(house, listOf(numeric("grid", 300.0), numeric("solar", 49.0), numeric("ac", 0.0), numeric("heater", 0.0)))
        assertNull(s.selfConsumption)
    }

    @Test
    fun `below 10 W an edge is idle`() {
        val s = build(house, listOf(numeric("grid", 5.0), numeric("solar", 3200.0), numeric("ac", 9.0), numeric("heater", 1700.0)))
        assertEquals(Flow.IDLE, s.node("grid")!!.flow)
        assertEquals(Flow.IDLE, s.node("ac")!!.flow)
        assertEquals(Flow.OUT, s.node("heater")!!.flow)
    }

    @Test
    fun `negative consumer is clamped`() {
        val s = build(house, houseStates.filterNot { it.uniqueId == "ac" } + numeric("ac", -3.0))
        assertEquals(0.0, s.node("ac")!!.value)
        assertEquals(Flow.IDLE, s.node("ac")!!.flow)
    }

    @Test
    fun `stale and unavailable are carried and aggregated into the derived house`() {
        val s = build(house, listOf(numeric("grid", -400.0, stale = true), numeric("solar", 3200.0), numeric("ac", 600.0, available = false), numeric("heater", 1700.0)))
        assertEquals(NodeStatus.STALE, s.node("grid")!!.status)
        assertEquals(NodeStatus.STALE, s.node("house")!!.status)
        assertEquals(NodeStatus.UNAVAILABLE, s.node("ac")!!.status)
        assertNull(s.node("ac")!!.value)
    }

    @Test
    fun `four consumers fold into two and more`() {
        val four = house + energyEntity("ev", EnergyRole.CONSUMER, name = "EV") + energyEntity("pump", EnergyRole.CONSUMER, name = "Pump")
        val s = build(four, houseStates + numeric("ev", 3000.0) + numeric("pump", 50.0))
        assertEquals(listOf("ev", "heater", "more"), s.diagram.filter { it.kind == NodeKind.CONSUMER || it.kind == NodeKind.MORE }.map { it.id })
        val more = s.node("more")!!
        assertEquals(2, more.count)
        assertEquals(650.0, more.value)
        assertEquals(listOf("ev", "heater", "ac", "pump"), s.consumers.map { it.id })
        assertEquals(4, s.cards.count { it.kind == NodeKind.CONSUMER })
    }

    @Test
    fun `override order comes before power order`() {
        val ordered = listOf(
            energyEntity("ac", EnergyRole.CONSUMER, order = 1),
            energyEntity("heater", EnergyRole.CONSUMER),
        )
        val s = build(ordered, listOf(numeric("ac", 10.0), numeric("heater", 2000.0)))
        assertEquals(listOf("ac", "heater"), s.consumers.map { it.id })
    }

    @Test
    fun `order is throttled for five seconds`() {
        val first = build(house, houseStates, now = 0)
        assertEquals(listOf("heater", "ac"), first.consumers.map { it.id })
        val swapped = houseStates.filterNot { it.uniqueId == "ac" } + numeric("ac", 2500.0)
        assertEquals(listOf("heater", "ac"), build(house, swapped, now = 4_999, previous = first.order).consumers.map { it.id })
        assertEquals(listOf("ac", "heater"), build(house, swapped, now = 5_000, previous = first.order).consumers.map { it.id })
    }

    @Test
    fun `order is recomputed when the consumer set changes`() {
        val first = build(house, houseStates, now = 0)
        val fewer = house.filterNot { it.uniqueId == "heater" }
        val s = build(fewer, houseStates, now = 1_000, previous = first.order)
        assertEquals(listOf("ac"), s.consumers.map { it.id })
        assertEquals(1_000, s.order!!.atMillis)
    }

    @Test
    fun `water sits bottom left without a battery and only in cards with one`() {
        val water = energyEntity("water", EnergyRole.WATER, unit = "L")
        val alone = build(house + water, houseStates + numeric("water", 312.0))
        assertEquals(312.0, alone.node("water")!!.value)
        assertEquals("L", alone.node("water")!!.unit)
        assertTrue(alone.diagram.any { it.id == "water" })
        val withBattery = build(house + water + energyEntity("bat", EnergyRole.BATTERY), houseStates + numeric("water", 312.0) + numeric("bat", 0.0))
        assertTrue(withBattery.diagram.none { it.id == "water" })
        assertTrue(withBattery.cards.any { it.id == "water" })
    }

    @Test
    fun `consumers alone still show a house node`() {
        val s = build(listOf(energyEntity("ac", EnergyRole.CONSUMER)), listOf(numeric("ac", 300.0)))
        assertNull(s.node("house")!!.value)
        assertTrue(s.diagram.any { it.id == "ac" })
    }
}
