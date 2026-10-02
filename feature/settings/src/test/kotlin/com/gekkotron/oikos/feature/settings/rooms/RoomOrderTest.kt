package com.gekkotron.oikos.feature.settings.rooms

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import org.junit.Test
import kotlin.test.assertEquals

class RoomOrderTest {
    private val bath = Area("bath", "Bath", icon = "bath", order = 0)
    private val living = Area("living", "Living", order = 1)
    private val julien = Area("julien", "Julien") // implicit: computed, never declared
    private val unassigned = Area(UNASSIGNED_AREA, UNASSIGNED_AREA)

    @Test
    fun `reorders and numbers every room, keeping names and icons`() {
        assertEquals(
            listOf(Area("living", "Living", order = 0), Area("julien", "Julien", order = 1), Area("bath", "Bath", icon = "bath", order = 2)),
            RoomOrder.apply(listOf(bath, living, julien, unassigned), listOf("living", "julien", "bath")),
        )
    }

    @Test
    fun `unassigned is never written, even if asked`() {
        val out = RoomOrder.apply(listOf(bath, unassigned), listOf(UNASSIGNED_AREA, "bath"))
        assertEquals(listOf(Area("bath", "Bath", icon = "bath", order = 0)), out)
    }

    @Test
    fun `a room that appeared after the drag started is kept, after the ordered ones`() {
        val garage = Area("garage", "Garage")
        assertEquals(
            listOf(Area("living", "Living", order = 0), Area("bath", "Bath", icon = "bath", order = 1), Area("garage", "Garage", order = 2)),
            RoomOrder.apply(listOf(bath, living, garage), listOf("living", "bath")),
        )
    }

    @Test
    fun `an id that is gone is ignored`() {
        assertEquals(
            listOf(Area("bath", "Bath", icon = "bath", order = 0)),
            RoomOrder.apply(listOf(bath), listOf("office", "bath")),
        )
    }
}
