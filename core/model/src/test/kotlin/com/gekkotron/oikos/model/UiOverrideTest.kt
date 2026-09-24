package com.gekkotron.oikos.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UiOverrideTest {
    @Test
    fun `default override encodes to empty object`() {
        assertEquals("{}", UiOverrides.encode(UiOverride()))
    }

    @Test
    fun `full override round-trips`() {
        val o = UiOverride(
            name = "Lampe salon", area = "salon", hidden = true, order = 3,
            cardStyle = "hero_weather", favorite = true, energyRole = EnergyRole.WATER_HEATER,
        )
        val json = UiOverrides.encode(o)
        assertEquals(o, UiOverrides.decode(json))
        assert("\"energyRole\":\"water_heater\"" in json) { json }
    }

    @Test
    fun `unknown keys are ignored`() {
        assertEquals(UiOverride(name = "X"), UiOverrides.decode("""{"name":"X","future":42}"""))
    }

    @Test
    fun `unknown energy role becomes null instead of failing`() {
        assertEquals(UiOverride(name = "X"), UiOverrides.decode("""{"name":"X","energyRole":"battery"}"""))
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
}
