package com.gekkotron.oikos.feature.energy.presentation

import com.gekkotron.oikos.feature.energy.energyEntity
import com.gekkotron.oikos.feature.energy.numeric
import com.gekkotron.oikos.feature.energy.unknown
import com.gekkotron.oikos.model.ConsumerIcon
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
        assertEquals(listOf("solar", "grid", "house", "consumer:heater", "consumer:ac"), s.diagram.map { it.id })
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
        assertEquals(Flow.IDLE, s.node("consumer:ac")!!.flow)
        assertEquals(Flow.OUT, s.node("consumer:heater")!!.flow)
    }

    @Test
    fun `negative consumer is clamped`() {
        val s = build(house, houseStates.filterNot { it.uniqueId == "ac" } + numeric("ac", -3.0))
        assertEquals(0.0, s.node("consumer:ac")!!.value)
        assertEquals(Flow.IDLE, s.node("consumer:ac")!!.flow)
    }

    @Test
    fun `stale and unavailable are carried and aggregated into the derived house`() {
        val s = build(house, listOf(numeric("grid", -400.0, stale = true), numeric("solar", 3200.0), numeric("ac", 600.0, available = false), numeric("heater", 1700.0)))
        assertEquals(NodeStatus.STALE, s.node("grid")!!.status)
        assertEquals(NodeStatus.STALE, s.node("house")!!.status)
        assertEquals(NodeStatus.UNAVAILABLE, s.node("consumer:ac")!!.status)
        assertNull(s.node("consumer:ac")!!.value)
    }

    @Test
    fun `four consumers fold into two and more`() {
        val four = house + energyEntity("ev", EnergyRole.CONSUMER, name = "EV") + energyEntity("pump", EnergyRole.CONSUMER, name = "Pump")
        val s = build(four, houseStates + numeric("ev", 3000.0) + numeric("pump", 50.0))
        assertEquals(listOf("consumer:ev", "consumer:heater", "more"), s.diagram.filter { it.kind == NodeKind.CONSUMER || it.kind == NodeKind.MORE }.map { it.id })
        val more = s.node("more")!!
        assertEquals(2, more.count)
        assertEquals(650.0, more.value)
        assertEquals(listOf("consumer:ev", "consumer:heater", "consumer:ac", "consumer:pump"), s.consumers.map { it.id })
        assertEquals(4, s.cards.count { it.kind == NodeKind.CONSUMER })
    }

    @Test
    fun `override order comes before power order`() {
        val ordered = listOf(
            energyEntity("ac", EnergyRole.CONSUMER, order = 1),
            energyEntity("heater", EnergyRole.CONSUMER),
        )
        val s = build(ordered, listOf(numeric("ac", 10.0), numeric("heater", 2000.0)))
        assertEquals(listOf("consumer:ac", "consumer:heater"), s.consumers.map { it.id })
    }

    @Test
    fun `order is throttled for five seconds`() {
        val first = build(house, houseStates, now = 0)
        assertEquals(listOf("consumer:heater", "consumer:ac"), first.consumers.map { it.id })
        val swapped = houseStates.filterNot { it.uniqueId == "ac" } + numeric("ac", 2500.0)
        assertEquals(listOf("consumer:heater", "consumer:ac"), build(house, swapped, now = 4_999, previous = first.order).consumers.map { it.id })
        assertEquals(listOf("consumer:ac", "consumer:heater"), build(house, swapped, now = 5_000, previous = first.order).consumers.map { it.id })
    }

    @Test
    fun `order is recomputed when the consumer set changes`() {
        val first = build(house, houseStates, now = 0)
        val fewer = house.filterNot { it.uniqueId == "heater" }
        val s = build(fewer, houseStates, now = 1_000, previous = first.order)
        assertEquals(listOf("consumer:ac"), s.consumers.map { it.id })
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
        assertTrue(s.diagram.any { it.id == "consumer:ac" })
    }

    @Test
    fun `unavailable solar leaves the derived house unknown, not unavailable`() {
        val s = build(house, listOf(numeric("grid", 900.0), numeric("solar", 0.0, available = false), numeric("ac", 300.0), numeric("heater", 0.0)))
        assertEquals(NodeStatus.UNAVAILABLE, s.node("solar")!!.status)
        assertNull(s.node("house")!!.value)
        assertEquals(NodeStatus.LIVE, s.node("house")!!.status)
    }

    @Test
    fun `a consumer whose unique id is a role id does not collide`() {
        val s = build(listOf(energyEntity("grid", EnergyRole.GRID), energyEntity("house", EnergyRole.CONSUMER, name = "House plug")), listOf(numeric("grid", 500.0), numeric("house", 200.0)))
        assertEquals(s.cards.size, s.cards.map { it.id }.toSet().size)
        assertEquals(500.0, s.node("house")!!.value)
        assertEquals(200.0, s.node("consumer:house")!!.value)
    }

    @Test
    fun `role nodes list their sensors and the sensors carry raw values`() {
        val s = build(
            listOf(
                energyEntity("s1", EnergyRole.SOLAR, name = "Inverter B"), energyEntity("s2", EnergyRole.SOLAR, unit = "kW", name = "Inverter A"),
                energyEntity("imp", EnergyRole.GRID, name = "Import"), energyEntity("exp", EnergyRole.GRID_EXPORT, name = "Export", invert = false),
            ),
            listOf(numeric("s1", 1000.0), numeric("s2", 0.5), numeric("imp", 0.0), numeric("exp", 700.0)),
        )
        assertEquals(listOf("s2", "s1"), s.node("solar")!!.sensorIds)
        assertEquals(listOf("exp", "imp"), s.node("grid")!!.sensorIds)
        assertEquals(emptyList(), s.node("house")!!.sensorIds)
        val a = s.sensors.getValue("s2")
        assertEquals(SensorUi("s2", "Inverter A", 0.5, "kW", NodeStatus.LIVE, EnergyRole.SOLAR, invert = false, icon = null), a)
    }

    @Test
    fun `consumers and the fold list their sensors`() {
        val four = house + energyEntity("ev", EnergyRole.CONSUMER, name = "EV") + energyEntity("pump", EnergyRole.CONSUMER, name = "Pump")
        val s = build(four, houseStates + numeric("ev", 3000.0) + numeric("pump", 50.0))
        assertEquals(listOf("heater"), s.node("consumer:heater")!!.sensorIds)
        assertEquals(listOf("ac", "pump"), s.node("more")!!.sensorIds)
        assertTrue(s.sensors.keys.containsAll(listOf("grid", "solar", "ac", "heater", "ev", "pump")))
    }

    @Test
    fun `a measured house lists its meter`() {
        val s = build(house + energyEntity("load", EnergyRole.HOUSE), houseStates + numeric("load", 2500.0))
        assertEquals(listOf("load"), s.node("house")!!.sensorIds)
    }

    @Test
    fun `a consumer's icon reaches its node and sensor`() {
        val s = build(listOf(energyEntity("oven", EnergyRole.CONSUMER, icon = "oven")), listOf(numeric("oven", 900.0)))
        assertEquals(ConsumerIcon.OVEN, s.node("consumer:oven")!!.icon)
        assertEquals(ConsumerIcon.OVEN, s.sensors.getValue("oven").icon)
    }

    @Test
    fun `an unknown or absent icon key is no icon`() {
        val s = build(
            listOf(energyEntity("a", EnergyRole.CONSUMER, icon = "toaster"), energyEntity("b", EnergyRole.CONSUMER)),
            listOf(numeric("a", 100.0), numeric("b", 50.0)),
        )
        assertNull(s.node("consumer:a")!!.icon)
        assertNull(s.node("consumer:b")!!.icon)
    }

    private fun counter(id: String, device: String? = "d", unit: String = "kWh", name: String = id): Entity =
        energyEntity(id, null, unit = unit, name = name).copy(deviceId = device)

    private fun onDevice(e: Entity, device: String = "d"): Entity = e.copy(deviceId = device)

    @Test
    fun `a consumer's card carries its paired lifetime total`() {
        val heater = onDevice(energyEntity("heater", EnergyRole.CONSUMER, name = "Heater"))
        val s = build(listOf(heater, counter("heater_kwh")), listOf(numeric("heater", 900.0), numeric("heater_kwh", 1234.5)))
        val n = s.node("consumer:heater")!!
        assertTrue(n.hasMeter)
        assertEquals(1234.5, n.totalKwh)
        assertEquals(NodeStatus.LIVE, n.totalStatus)
        assertEquals(900.0, n.value)
    }

    @Test
    fun `a Wh meter is converted to kWh`() {
        val heater = onDevice(energyEntity("heater", EnergyRole.CONSUMER))
        val s = build(listOf(heater, counter("m", unit = "Wh")), listOf(numeric("heater", 100.0), numeric("m", 2500.0)))
        assertEquals(2.5, s.node("consumer:heater")!!.totalKwh)
    }

    @Test
    fun `an unknown meter gives no total and an unavailable status`() {
        val heater = onDevice(energyEntity("heater", EnergyRole.CONSUMER))
        val s = build(listOf(heater, counter("m")), listOf(numeric("heater", 100.0), numeric("m", 1.0, available = false)))
        val n = s.node("consumer:heater")!!
        assertTrue(n.hasMeter)
        assertNull(n.totalKwh)
        assertEquals(NodeStatus.UNAVAILABLE, n.totalStatus)
        val unknownState = build(listOf(heater, counter("m")), listOf(numeric("heater", 100.0), unknown("m")))
        assertNull(unknownState.node("consumer:heater")!!.totalKwh)
    }

    @Test
    fun `a stale meter marks the total stale`() {
        val heater = onDevice(energyEntity("heater", EnergyRole.CONSUMER))
        val s = build(listOf(heater, counter("m")), listOf(numeric("heater", 100.0), numeric("m", 5.0, stale = true)))
        assertEquals(NodeStatus.STALE, s.node("consumer:heater")!!.totalStatus)
    }

    @Test
    fun `a derived house has no meter`() {
        val s = build(house + counter("x", device = "other"), houseStates)
        assertEquals(false, s.node("house")!!.hasMeter)
    }

    /** This house's PJ-1203A: power_a = grid (signed), power_b = solar, unnamed so only the ids differ. */
    private val clamp = listOf(
        onDevice(energyEntity("0x_power_a_z", EnergyRole.GRID, name = "powers/grid_solar"), "g"),
        onDevice(energyEntity("0x_power_b_z", EnergyRole.SOLAR, name = "powers/grid_solar"), "g"),
        counter("0x_energy_a_z", device = "g"),
        counter("0x_energy_b_z", device = "g"),
        counter("0x_energy_produced_a_z", device = "g"),
        counter("0x_energy_produced_b_z", device = "g"),
    )
    private val clampStates = listOf(
        numeric("0x_power_a_z", 300.0), numeric("0x_power_b_z", 500.0),
        numeric("0x_energy_a_z", 100.0), numeric("0x_energy_b_z", 40.0),
        numeric("0x_energy_produced_a_z", 30.0), numeric("0x_energy_produced_b_z", 200.0),
    )

    @Test
    fun `a derived house totals grid import minus export plus solar production minus the inverter's draw`() {
        val s = build(clamp, clampStates)
        assertEquals(100.0, s.node("grid")!!.totalKwh)
        // A clamp on the inverter line: energy_produced_b is the production, energy_b the inverter's standby draw.
        assertEquals(200.0, s.node("solar")!!.totalKwh)
        val house = s.node("house")!!
        assertTrue(house.hasMeter)
        assertEquals(100.0 - 30.0 + 200.0 - 40.0, house.totalKwh)
    }

    @Test
    fun `a derived house counts a grid_export meter as export`() {
        val grid = onDevice(energyEntity("grid", EnergyRole.GRID), "g")
        val solar = onDevice(energyEntity("solar", EnergyRole.SOLAR), "s")
        val export = onDevice(energyEntity("export", EnergyRole.GRID_EXPORT), "x")
        val s = build(
            listOf(grid, solar, export, counter("g_kwh", device = "g"), counter("s_kwh", device = "s"), counter("x_kwh", device = "x")),
            listOf(numeric("grid", 1.0), numeric("solar", 1.0), numeric("export", 1.0), numeric("g_kwh", 10.0), numeric("s_kwh", 8.0), numeric("x_kwh", 3.0)),
        )
        assertEquals(15.0, s.node("house")!!.totalKwh)
    }

    @Test
    fun `a derived house without an export counter for its solar has no total`() {
        val noExport = clamp.filterNot { it.uniqueId == "0x_energy_produced_a_z" }
        assertEquals(false, build(noExport, clampStates).node("house")!!.hasMeter)
    }

    @Test
    fun `a derived house follows its inputs' status`() {
        val s = build(clamp, clampStates.map { if (it.uniqueId == "0x_energy_produced_a_z") numeric(it.uniqueId, 30.0, available = false) else it })
        assertNull(s.node("house")!!.totalKwh)
        assertEquals(NodeStatus.UNAVAILABLE, s.node("house")!!.totalStatus)
    }

    @Test
    fun `grid totals ignore the export counter`() {
        val grid = onDevice(energyEntity("grid", EnergyRole.GRID), "g")
        val export = onDevice(energyEntity("export", EnergyRole.GRID_EXPORT), "x")
        val s = build(
            listOf(grid, export, counter("grid_kwh", device = "g"), counter("export_kwh", device = "x")),
            listOf(numeric("grid", 100.0), numeric("export", 50.0), numeric("grid_kwh", 10.0), numeric("export_kwh", 99.0)),
        )
        assertEquals(10.0, s.node("grid")!!.totalKwh)
        val exportOnly = build(listOf(export, counter("export_kwh", device = "x")), listOf(numeric("export", 50.0), numeric("export_kwh", 99.0)))
        assertEquals(false, exportOnly.node("grid")!!.hasMeter)
    }

    @Test
    fun `solar sums its inverters' meters`() {
        val a = onDevice(energyEntity("s1", EnergyRole.SOLAR), "a")
        val b = onDevice(energyEntity("s2", EnergyRole.SOLAR), "b")
        val s = build(
            listOf(a, b, counter("k1", device = "a"), counter("k2", device = "b")),
            listOf(numeric("s1", 1.0), numeric("s2", 1.0), numeric("k1", 10.0), numeric("k2", 5.0)),
        )
        assertEquals(15.0, s.node("solar")!!.totalKwh)
    }

    @Test
    fun `the sheet gets every counter by name and each sensor's pairing`() {
        val heater = onDevice(energyEntity("heater", EnergyRole.CONSUMER).let { it.copy(ui = it.ui.copy(energyMeter = "z")) })
        val s = build(listOf(heater, counter("a", name = "Zeta"), counter("z", device = null, name = "Alpha")), listOf(numeric("heater", 1.0)))
        assertEquals(listOf("z", "a"), s.meters.map { it.id })
        val ui = s.sensors.getValue("heater")
        assertEquals("z", ui.meterId)
        assertEquals("a", ui.autoMeterId)
    }
}
