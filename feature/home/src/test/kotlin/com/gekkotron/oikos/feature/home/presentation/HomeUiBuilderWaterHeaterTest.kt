package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeUiBuilderWaterHeaterTest {
    private fun build(
        mode: String?,
        modes: List<String> = listOf("off", "eco", "performance"),
        companions: List<Pair<String, String>> = listOf("force_power" to "wh_device"),
    ): CardUi {
        val wh = testEntity(
            "wh", Kind.WATER_HEATER, "Water heater", area = "garage", deviceId = "wh_device",
            capabilities = Capabilities(hvacModes = modes, minTemp = 40.0, maxTemp = 65.0, tempStep = 1.0),
        )
        val numbers = companions.map { (id, device) ->
            testEntity(id, Kind.NUMBER, "Force power", area = "garage", deviceId = device,
                capabilities = Capabilities(min = 0.0, max = 100.0, step = 10.0))
        }
        val entities = (listOf(wh) + numbers).associateBy { it.uniqueId }
        val states = mapOf("wh" to EntityState("wh", StateValue.Climate(mode, 60.0, 52.0)))
        val ui = HomeUiBuilder.build(
            entities, states, listOf(Area("garage", "Garage")), RoomFilter.All,
            LinkState.Connected(TransportMode.DEMO), Locale.US,
        )
        return ui.card("wh") ?: error("water heater card missing")
    }

    @Test
    fun `tap opens the sheet`() {
        assertEquals(TapAction.OPEN_SHEET, build("eco").tap)
        assertTrue(build("eco").hasSheet)
    }

    @Test
    fun `on is true only in performance`() {
        assertTrue(build("performance").on)
        assertEquals(false, build("eco").on)
        assertEquals(false, build("off").on)
        assertEquals(false, build(null).on)
    }

    @Test
    fun `force toggle only when both performance and eco`() {
        assertEquals(false, build("eco").forceToggle)
        assertEquals(true, build("performance").forceToggle)
        assertNull(build("eco", modes = listOf("off", "electric", "gas")).forceToggle)
        assertNull(build("eco", modes = listOf("eco")).forceToggle)
    }

    @Test
    fun `water heater sheet shows same-device numbers as companions`() {
        val card = build("eco")
        assertEquals(listOf("force_power"), card.companions.map { it.id })
    }

    @Test
    fun `water heater sheet without companions`() {
        val card = build("eco", companions = emptyList())
        assertEquals(emptyList(), card.companions)
    }

    @Test
    fun `companions filter out numbers from other devices`() {
        val card = build("eco", companions = listOf("force_power" to "wh_device", "other" to "another_device"))
        assertEquals(listOf("force_power"), card.companions.map { it.id })
    }
}
