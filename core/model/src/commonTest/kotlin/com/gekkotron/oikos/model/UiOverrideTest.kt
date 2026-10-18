package com.gekkotron.oikos.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiOverrideTest {
    @Test
    fun `default override encodes to empty object`() {
        assertEquals("{}", UiOverrides.encode(UiOverride()))
    }

    @Test
    fun `full override round-trips`() {
        val o = UiOverride(
            name = "Lampe salon", area = "salon", hidden = true, order = 3,
            cardStyle = "hero_weather", favorite = true, energyRole = EnergyRole.CONSUMER, energyInvert = true,
            homePinned = true,
        )
        val json = UiOverrides.encode(o)
        assertEquals(o, UiOverrides.decode(json))
        assert("\"energyRole\":\"consumer\"" in json) { json }
    }

    @Test
    fun `unknown keys are ignored`() {
        assertEquals(UiOverride(name = "X"), UiOverrides.decode("""{"name":"X","future":42}"""))
    }

    @Test
    fun `unknown energy role becomes null instead of failing`() {
        assertEquals(UiOverride(name = "X"), UiOverrides.decode("""{"name":"X","energyRole":"nuclear"}"""))
    }

    @Test
    fun `invalid payloads decode to null`() {
        assertNull(UiOverrides.decode("not json"))
        assertNull(UiOverrides.decode("[1,2]"))
    }

    @Test
    fun `areas document decodes`() {
        val doc = OikosJson.decodeFromString(
            AreasDocument.serializer(),
            """{"areas":[{"id":"salon","name":"Salon","icon":"sofa","order":1}]}""",
        )
        assertEquals(listOf(Area("salon", "Salon", "sofa", 1)), doc.areas)
    }

    @Test
    fun `legacy water_heater decodes as consumer and re-encodes as consumer`() {
        val decoded = UiOverrides.decode("""{"energyRole":"water_heater"}""")!!
        assertEquals(EnergyRole.CONSUMER, decoded.energyRole)
        assertTrue(UiOverrides.encode(decoded).contains("\"consumer\""))
    }

    @Test
    fun `unknown role decodes as no role and keeps the other fields`() {
        val decoded = UiOverrides.decode("""{"energyRole":"heat_pump","favorite":true}""")!!
        assertEquals(null, decoded.energyRole)
        assertTrue(decoded.favorite)
    }

    @Test
    fun `energyInvert round-trips and is omitted when false`() {
        val inverted = UiOverride(energyRole = EnergyRole.GRID, energyInvert = true)
        assertEquals(inverted, UiOverrides.decode(UiOverrides.encode(inverted)))
        assertFalse(UiOverrides.encode(UiOverride(energyRole = EnergyRole.GRID)).contains("energyInvert"))
    }

    @Test
    fun `new roles use their json names`() {
        assertEquals(EnergyRole.GRID_EXPORT, UiOverrides.decode("""{"energyRole":"grid_export"}""")!!.energyRole)
        assertEquals(EnergyRole.BATTERY, UiOverrides.decode("""{"energyRole":"battery"}""")!!.energyRole)
    }

    @Test
    fun `only grid and battery are signed, only water is not power`() {
        assertEquals(setOf(EnergyRole.GRID, EnergyRole.BATTERY), EnergyRole.entries.filter { it.signed }.toSet())
        assertEquals(setOf(EnergyRole.WATER), EnergyRole.entries.filterNot { it.power }.toSet())
    }

    @Test
    fun `securityHidden round-trips and is omitted when false`() {
        val o = UiOverride(securityHidden = true)
        val json = UiOverrides.encode(o)
        assertEquals("""{"securityHidden":true}""", json)
        assertEquals(o, UiOverrides.decode(json))
        assertEquals("{}", UiOverrides.encode(UiOverride(securityHidden = false)))
    }

    @Test
    fun `energyIcon round-trips`() {
        val o = UiOverride(energyRole = EnergyRole.CONSUMER, energyIcon = "oven")
        val json = UiOverrides.encode(o)
        assertTrue(json.contains("\"energyIcon\":\"oven\""), json)
        val decoded = UiOverrides.decode(json)!!
        assertEquals(o, decoded)
        assertEquals(ConsumerIcon.OVEN, ConsumerIcon.of(decoded.energyIcon))
    }

    @Test
    fun `unknown energyIcon decodes and maps to no icon`() {
        val decoded = UiOverrides.decode("""{"energyIcon":"toaster","favorite":true}""")!!
        assertEquals("toaster", decoded.energyIcon)
        assertTrue(decoded.favorite)
        assertNull(ConsumerIcon.of(decoded.energyIcon))
        assertNull(ConsumerIcon.of(null))
    }

    @Test
    fun `absent energyIcon is null and omitted`() {
        assertNull(UiOverrides.decode("{}")!!.energyIcon)
        assertFalse(UiOverrides.encode(UiOverride()).contains("energyIcon"))
    }

    @Test
    fun `energyMeter round-trips`() {
        val o = UiOverride(energyRole = EnergyRole.SOLAR, energyMeter = "solar_kwh")
        val json = UiOverrides.encode(o)
        assertTrue(json.contains("\"energyMeter\":\"solar_kwh\""), json)
        assertEquals(o, UiOverrides.decode(json))
    }

    @Test
    fun `absent energyMeter is null and omitted`() {
        assertNull(UiOverrides.decode("{}")!!.energyMeter)
        assertFalse(UiOverrides.encode(UiOverride()).contains("energyMeter"))
    }

    @Test
    fun `action flags round-trip and only non-defaults are encoded`() {
        val o = UiOverride(securityAction = true, securityAsk = false)
        val json = UiOverrides.encode(o)
        assertEquals(o, UiOverrides.decode(json))
        assertEquals("""{"securityAction":true,"securityAsk":false}""", json)
        assertEquals(true, UiOverrides.decode("{}")?.securityAsk)
    }

    @Test
    fun `ungrouped round-trips and defaults to false`() {
        assertEquals(UiOverride(ungrouped = true), UiOverrides.decode(UiOverrides.encode(UiOverride(ungrouped = true))))
        assertFalse(UiOverrides.decode("""{"name":"X"}""")!!.ungrouped)
    }

    @Test
    fun `lightOnBrightness and climatePresets round-trip and are omitted when null`() {
        val o = UiOverride(lightOnPreset = "white", lightOnBrightness = 80, climatePresets = "19,21,23")
        val json = UiOverrides.encode(o)
        assertEquals(o, UiOverrides.decode(json))
        assertFalse(UiOverrides.encode(UiOverride()).contains("lightOnBrightness"))
        assertFalse(UiOverrides.encode(UiOverride()).contains("climatePresets"))
    }

    @Test
    fun `ClimatePresets parse drops blanks, caps at MAX and keeps order`() {
        assertEquals(listOf(19.0, 21.0, 23.0), ClimatePresets.parse("19, 21, 23"))
        assertEquals(listOf(19.5, 22.0), ClimatePresets.parse("19.5 ; 22"))
        assertEquals(emptyList(), ClimatePresets.parse(null))
        assertEquals(emptyList(), ClimatePresets.parse("   "))
        // Garbage tokens drop; good ones survive.
        assertEquals(listOf(20.0), ClimatePresets.parse("foo, 20, bar"))
        // Duplicates are removed, cap applies in input order.
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0), ClimatePresets.parse("1,2,3,4,5,6,7,8"))
        assertEquals(listOf(1.0, 2.0), ClimatePresets.parse("1,2,1,2"))
    }

    @Test
    fun `ClimatePresets parseOrDefault falls back when the override is empty`() {
        assertEquals(ClimatePresets.DEFAULTS, ClimatePresets.parseOrDefault(null))
        assertEquals(ClimatePresets.DEFAULTS, ClimatePresets.parseOrDefault(""))
        assertEquals(ClimatePresets.DEFAULTS, ClimatePresets.parseOrDefault("   "))
        // A user-set override still wins over the defaults.
        assertEquals(listOf(19.0, 21.0), ClimatePresets.parseOrDefault("19, 21"))
    }
}
