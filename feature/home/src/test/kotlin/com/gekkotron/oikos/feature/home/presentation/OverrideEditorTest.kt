package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.feature.home.FakeEntitySource
import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.model.UiOverride
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverrideEditorTest {
    private val source = FakeEntitySource().apply {
        entities.value = listOf(
            testEntity("a", Kind.LIGHT, ui = UiOverride(favorite = true)),
            testEntity("b", Kind.SWITCH, area = "kitchen"),
            testEntity("c", Kind.SENSOR),
        ).associateBy { it.uniqueId }
        areas.value = listOf(Area("kitchen", "Kitchen"), Area(UNASSIGNED_AREA, UNASSIGNED_AREA))
    }
    private val editor = OverrideEditor(source)

    @Test
    fun `assignArea keeps the rest of each override and skips entities already there`() = runTest {
        assertTrue(editor.assignArea(listOf("a", "b", "missing"), "kitchen"))
        assertEquals(mapOf("a" to UiOverride(favorite = true, area = "kitchen")), source.overrides)
    }

    @Test
    fun `no room is written as Unassigned`() = runTest {
        assertTrue(editor.assignArea(listOf("b"), UNASSIGNED_AREA))
        assertEquals(UiOverride(area = UNASSIGNED_AREA), source.overrides["b"])
    }

    @Test
    fun `addRoom writes the current areas plus the new one, without Unassigned`() = runTest {
        assertTrue(editor.addRoom(Area("office", "Office")))
        assertEquals(listOf(listOf(Area("kitchen", "Kitchen"), Area("office", "Office"))), source.areaWrites)
    }

    @Test
    fun `failures are reported as false, never thrown`() = runTest {
        source.failWrites = true
        assertFalse(editor.save("a", UiOverride(hidden = true)))
        assertFalse(editor.assignArea(listOf("a"), "kitchen"))
        assertFalse(editor.addRoom(Area("office", "Office")))
    }

    @Test
    fun `an id that cannot be a topic is a failure, not a crash`() = runTest {
        val strict = OverrideEditor(object : com.gekkotron.oikos.source.EntitySource by source {
            override suspend fun setOverride(uniqueId: String, override: UiOverride) = throw IllegalArgumentException("bad id")
        })
        assertFalse(strict.save("a#b", UiOverride()))
    }

    @Test
    fun `setGroupName declares an implicit room and keeps the others`() = runTest {
        val source = FakeEntitySource().apply {
            areas.value = listOf(Area("kitchen", "Kitchen"), Area("salon", "Salon"), Area(UNASSIGNED_AREA, UNASSIGNED_AREA))
        }
        assertTrue(OverrideEditor(source).setGroupName("salon", "cover", "Baie"))
        assertEquals(
            listOf(Area("kitchen", "Kitchen"), Area("salon", "Salon", groupNames = mapOf("cover" to "Baie"))),
            source.areaWrites.single(),
        )
    }

    @Test
    fun `a blank group name removes the custom name`() = runTest {
        val source = FakeEntitySource().apply { areas.value = listOf(Area("salon", "Salon", groupNames = mapOf("cover" to "Baie", "light" to "Spots"))) }
        assertTrue(OverrideEditor(source).setGroupName("salon", "cover", "  "))
        assertEquals(mapOf("light" to "Spots"), source.areaWrites.single().single().groupNames)
    }

    @Test
    fun `ungroup flags each device and keeps the rest of its override`() = runTest {
        val source = FakeEntitySource().apply {
            entities.value = mapOf("a" to testEntity("a", Kind.COVER, area = "salon", ui = UiOverride(favorite = true)))
        }
        assertTrue(OverrideEditor(source).ungroup(listOf("a")))
        assertEquals(UiOverride(favorite = true, ungrouped = true), source.overrides["a"])
    }

    @Test
    fun `setGroupName on an unknown room fails without writing`() = runTest {
        val source = FakeEntitySource()
        assertFalse(OverrideEditor(source).setGroupName("nowhere", "cover", "X"))
        assertTrue(source.areaWrites.isEmpty())
    }
}
