package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeUiBuilderTest {
    private val areas = listOf(Area("living", "Living room"), Area("kitchen", "Kitchen"), Area("garage", "Garage"))
    private val link = LinkState.Connected(TransportMode.DEMO)

    private fun build(
        vararg entities: Entity,
        states: Map<String, EntityState> = emptyMap(),
        selected: RoomFilter = RoomFilter.All,
        areaList: List<Area> = areas,
        labels: LabelText = LabelText { null },
    ) = HomeUiBuilder.build(entities.associateBy { it.uniqueId }, states, areaList, selected, link, Locale.US, labels)

    @Test
    fun `empty source is loading`() {
        val s = build()
        assertTrue(s.loading)
        assertEquals(listOf(ChipUi(RoomFilter.All, null)), s.chips)
    }

    @Test
    fun `hidden entities are dropped and the hero is taken out of the grid`() {
        val s = build(
            testEntity("lamp", Kind.LIGHT, area = "living"),
            testEntity("secret", Kind.SWITCH, area = "living", ui = UiOverride(hidden = true)),
            testEntity("outdoor", Kind.SENSOR, ui = UiOverride(cardStyle = HomeUiBuilder.HERO_WEATHER)),
        )
        assertFalse(s.loading)
        assertEquals(listOf("lamp"), s.cards.map { it.id })
        assertEquals("outdoor", s.hero?.id)
        assertEquals("outdoor", s.card("outdoor")?.id)
    }

    @Test
    fun `chips list All, Favourites when any, then only areas with visible cards in area order`() {
        val s = build(
            testEntity("coffee", Kind.SWITCH, area = "kitchen"),
            testEntity("lamp", Kind.LIGHT, area = "living", ui = UiOverride(favorite = true)),
            testEntity("x", Kind.SWITCH),
        )
        assertEquals(
            listOf(RoomFilter.All, RoomFilter.Favorites, RoomFilter.Area("living"), RoomFilter.Area("kitchen")),
            s.chips.map { it.filter },
        )
        assertEquals("Living room", s.chips[2].label)
    }

    @Test
    fun `unassigned chip has a null label for localisation`() {
        val s = build(testEntity("x", Kind.SWITCH), areaList = areas + Area(UNASSIGNED_AREA, UNASSIGNED_AREA))
        assertEquals(ChipUi(RoomFilter.Area(UNASSIGNED_AREA), null), s.chips.last())
        assertNull(s.cards.single().areaName)
    }

    @Test
    fun `filters select favourites or one area`() {
        val entities = arrayOf(
            testEntity("coffee", Kind.SWITCH, area = "kitchen"),
            testEntity("lamp", Kind.LIGHT, area = "living", ui = UiOverride(favorite = true)),
        )
        assertEquals(listOf("lamp"), build(*entities, selected = RoomFilter.Favorites).cards.map { it.id })
        assertEquals(listOf("coffee"), build(*entities, selected = RoomFilter.Area("kitchen")).cards.map { it.id })
    }

    @Test
    fun `selection of a vanished area falls back to All`() {
        val s = build(testEntity("coffee", Kind.SWITCH, area = "kitchen"), selected = RoomFilter.Area("garage"))
        assertEquals(RoomFilter.All, s.selected)
        assertEquals(listOf("coffee"), s.cards.map { it.id })
        val noFavourites = build(testEntity("coffee", Kind.SWITCH, area = "kitchen"), selected = RoomFilter.Favorites)
        assertEquals(RoomFilter.All, noFavourites.selected)
    }

    @Test
    fun `cards sort by override order, then area order, then name`() {
        val s = build(
            testEntity("b", Kind.SWITCH, name = "Blender", area = "kitchen"),
            testEntity("a", Kind.SWITCH, name = "apple", area = "kitchen"),
            testEntity("z", Kind.SWITCH, name = "Zeta", area = "living"),
            testEntity("first", Kind.SWITCH, name = "Last name", area = "garage", ui = UiOverride(order = 1)),
        )
        assertEquals(listOf("first", "z", "a", "b"), s.cards.map { it.id })
    }

    @Test
    fun `tap actions, sheets, on-state and wide cards follow the kind`() {
        val s = build(
            testEntity("light", Kind.LIGHT, capabilities = Capabilities(brightness = true)),
            testEntity("switch", Kind.SWITCH),
            testEntity("cover", Kind.COVER),
            testEntity("button", Kind.BUTTON),
            testEntity("power", Kind.SENSOR, deviceClass = "power"),
            testEntity("temp", Kind.SENSOR, deviceClass = "temperature"),
            states = mapOf(
                "light" to EntityState("light", StateValue.Light(true, 10)),
                "switch" to EntityState("switch", StateValue.OnOff(false)),
                "cover" to EntityState("cover", StateValue.Cover(CoverState.CLOSED, 0)),
            ),
        )
        val byId = s.cards.associateBy { it.id }
        assertEquals(TapAction.TOGGLE, byId.getValue("light").tap)
        assertTrue(byId.getValue("light").hasSheet)
        assertTrue(byId.getValue("light").on)
        assertEquals(TapAction.TOGGLE, byId.getValue("switch").tap)
        assertFalse(byId.getValue("switch").hasSheet)
        assertEquals(TapAction.OPEN_SHEET, byId.getValue("cover").tap)
        assertFalse(byId.getValue("cover").on)
        assertEquals(TapAction.PRESS, byId.getValue("button").tap)
        assertEquals(TapAction.NONE, byId.getValue("temp").tap)
        assertTrue(byId.getValue("power").wide)
        assertFalse(byId.getValue("temp").wide)
    }

    @Test
    fun `availability and pending come from the state, defaulting to available`() {
        val s = build(
            testEntity("plug", Kind.SWITCH),
            testEntity("fresh", Kind.SWITCH),
            states = mapOf("plug" to EntityState("plug", StateValue.OnOff(false), available = false, pending = true)),
        )
        val byId = s.cards.associateBy { it.id }
        assertFalse(byId.getValue("plug").available)
        assertTrue(byId.getValue("plug").pending)
        assertTrue(byId.getValue("fresh").available)
        assertEquals(CardValue.None, byId.getValue("fresh").value)
    }

    @Test
    fun `room filter keys round-trip`() {
        for (f in listOf(RoomFilter.All, RoomFilter.Favorites, RoomFilter.Area("living room"))) {
            assertEquals(f, RoomFilter.fromKey(f.key))
        }
        assertEquals(RoomFilter.All, RoomFilter.fromKey("garbage"))
    }

    @Test
    fun `hidden entities get a trailing Hidden chip that lists only them`() {
        val lamp = testEntity("lamp", Kind.LIGHT, area = "living")
        val secret = testEntity("secret", Kind.SWITCH, area = "kitchen", ui = UiOverride(hidden = true))
        val all = build(lamp, secret)
        assertEquals(RoomFilter.Hidden, all.chips.last().filter)
        assertEquals(1, all.hiddenCount)
        assertEquals(listOf("lamp"), all.cards.map { it.id })
        val hidden = build(lamp, secret, selected = RoomFilter.Hidden)
        assertEquals(RoomFilter.Hidden, hidden.selected)
        assertEquals(listOf("secret"), hidden.cards.map { it.id })
        assertTrue(hidden.cards.single().hidden)
    }

    @Test
    fun `Hidden selection falls back to All when nothing is hidden any more`() {
        val s = build(testEntity("lamp", Kind.LIGHT), selected = RoomFilter.Hidden)
        assertEquals(RoomFilter.All, s.selected)
        assertEquals(0, s.hiddenCount)
        assertFalse(s.chips.any { it.filter == RoomFilter.Hidden })
    }

    @Test
    fun `cards carry their area id and override, and rooms exclude Unassigned`() {
        val s = build(
            testEntity("lamp", Kind.LIGHT, area = "living", ui = UiOverride(favorite = true)),
            testEntity("x", Kind.SWITCH),
            areaList = areas + Area(UNASSIGNED_AREA, UNASSIGNED_AREA),
        )
        val lamp = s.card("lamp")!!
        assertEquals("living", lamp.areaId)
        assertEquals(UiOverride(favorite = true), lamp.ui)
        assertEquals(UNASSIGNED_AREA, s.card("x")!!.areaId)
        assertEquals(areas, s.rooms)
    }

    @Test
    fun `hidden filter key round-trips`() {
        assertEquals(RoomFilter.Hidden, RoomFilter.fromKey(RoomFilter.Hidden.key))
    }

    @Test
    fun `an entity attached to another is skipped everywhere, card, hero, chips and hidden`() {
        val s = build(
            testEntity("cover", Kind.COVER, area = "living"),
            testEntity("moving", Kind.SENSOR, area = "living", partOf = "cover"),
            testEntity("hidden_moving", Kind.SENSOR, area = "living", partOf = "cover", ui = UiOverride(hidden = true)),
        )
        assertEquals(listOf("cover"), s.cards.map { it.id })
        assertEquals(0, s.hiddenCount)
        assertFalse(s.chips.any { it.filter == RoomFilter.Hidden })
    }

    @Test
    fun `a known label is translated after the device name, an override or an unknown label is shown as is`() {
        val fr = LabelText { if (it == "Local temperature") "Température locale" else null }
        fun labelled(id: String, name: String, label: String?, ui: UiOverride = UiOverride()) =
            testEntity(id, Kind.SENSOR, name = name, ui = ui).copy(deviceName = "Thermostat", label = label)
        val s = build(
            labelled("t", "Thermostat Local temperature", "Local temperature"),
            labelled("u", "Thermostat Frost guard", "Frost guard"),
            labelled("r", "Radiator", "Local temperature", ui = UiOverride(name = "Radiator")),
            labelled("m", "Thermostat", null),
            labels = fr,
        )
        assertEquals("Thermostat Température locale", s.card("t")?.name)
        assertEquals("Thermostat Frost guard", s.card("u")?.name)
        assertEquals("Radiator", s.card("r")?.name)
        assertEquals("Thermostat", s.card("m")?.name)
    }

    @Test
    fun `cards sort by their translated name`() {
        val fr = LabelText { mapOf("Power" to "Puissance", "Pressure" to "Pression")[it] }
        val s = build(
            testEntity("w", Kind.SENSOR, name = "Meter Power").copy(deviceName = "Meter", label = "Power"),
            testEntity("p", Kind.SENSOR, name = "Meter Pressure").copy(deviceName = "Meter", label = "Pressure"),
            labels = fr,
        )
        assertEquals(listOf("Meter Pression", "Meter Puissance"), s.cards.map { it.name })
    }

    @Test
    fun `a query searches every room, hidden devices included`() {
        val all = listOf(
            testEntity("lamp", Kind.LIGHT, "Floor lamp", area = "living"),
            testEntity("spots", Kind.LIGHT, "Kitchen spots", area = "kitchen"),
            testEntity("old", Kind.LIGHT, "Old lamp", area = "garage", ui = UiOverride(hidden = true)),
            testEntity("plug", Kind.SWITCH, "Plug", area = "garage"),
        ).associateBy { it.uniqueId }
        val s = HomeUiBuilder.build(all, emptyMap(), areas, RoomFilter.Area("kitchen"), link, Locale.US, query = "lamp")
        assertEquals(listOf("lamp", "old"), s.cards.map { it.id })
        assertTrue(s.cards.single { it.id == "old" }.hidden)
        assertEquals("lamp", s.query)
    }

    @Test
    fun `a query matches the room name`() {
        val all = listOf(testEntity("plug", Kind.SWITCH, "Plug", area = "garage"), testEntity("lamp", Kind.LIGHT, "Lamp", area = "living")).associateBy { it.uniqueId }
        assertEquals(listOf("plug"), HomeUiBuilder.build(all, emptyMap(), areas, RoomFilter.All, link, Locale.US, query = "garage").cards.map { it.id })
    }

    @Test
    fun `no match gives no cards`() {
        val all = listOf(testEntity("lamp", Kind.LIGHT, "Lamp", area = "living")).associateBy { it.uniqueId }
        assertTrue(HomeUiBuilder.build(all, emptyMap(), areas, RoomFilter.All, link, Locale.US, query = "zzz").cards.isEmpty())
    }
}
