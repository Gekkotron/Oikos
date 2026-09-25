package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AreaNamesTest {
    private val existing = listOf(Area("kitchen", "Kitchen"), Area("Salon", "Salon"), Area(UNASSIGNED_AREA, UNASSIGNED_AREA))

    @Test
    fun `a new name gets a lowercase ascii id`() {
        assertEquals(AreaNames.Resolved(Area("living_room", "Living room"), isNew = true), AreaNames.resolve("  Living   room ", existing))
        assertEquals("sejour", AreaNames.slug("Séjour"))
        assertEquals("salle_de_bain_2", AreaNames.slug("Salle de bain #2"))
    }

    @Test
    fun `a name matching an existing room ignoring case reuses it`() {
        assertEquals(AreaNames.Resolved(Area("kitchen", "Kitchen"), isNew = false), AreaNames.resolve("kitchen", existing))
        assertEquals(AreaNames.Resolved(Area("Salon", "Salon"), isNew = false), AreaNames.resolve("SALON", existing))
    }

    @Test
    fun `ids stay unique`() {
        val taken = listOf(Area("office", "Bureau"), Area("office_2", "Study"))
        assertEquals("office_3", AreaNames.resolve("Office", taken)?.area?.id)
    }

    @Test
    fun `names without letters or digits still get an id`() {
        assertEquals("room", AreaNames.resolve("客厅", emptyList())?.area?.id)
        assertEquals("room_2", AreaNames.resolve("🛋", listOf(Area("room", "客厅")))?.area?.id)
    }

    @Test
    fun `blank and reserved names are refused`() {
        assertNull(AreaNames.resolve("   ", existing))
        assertNull(AreaNames.resolve("unassigned", existing))
    }

    @Test
    fun `withArea drops Unassigned and appends`() {
        assertEquals(
            listOf(Area("kitchen", "Kitchen"), Area("Salon", "Salon"), Area("office", "Office")),
            AreaNames.withArea(existing, Area("office", "Office")),
        )
    }


    // Final review I2
    @Test
    fun `a typed name matching a room ignoring accents and punctuation reuses it`() {
        val fromPath = listOf(Area("sejour", "Sejour"), Area("salle_de_bain", "Salle de bain"))
        assertEquals(AreaNames.Resolved(Area("sejour", "Sejour"), isNew = false), AreaNames.resolve("Séjour", fromPath))
        assertEquals(AreaNames.Resolved(Area("salle_de_bain", "Salle de bain"), isNew = false), AreaNames.resolve("salle-de-bain", fromPath))
    }
}
