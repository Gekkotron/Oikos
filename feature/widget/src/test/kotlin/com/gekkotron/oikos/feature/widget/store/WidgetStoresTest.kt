package com.gekkotron.oikos.feature.widget.store

import com.gekkotron.oikos.feature.widget.model.LinkStatus
import com.gekkotron.oikos.feature.widget.model.TileIcon
import com.gekkotron.oikos.feature.widget.model.TileSnapshot
import com.gekkotron.oikos.feature.widget.model.TileValue
import com.gekkotron.oikos.feature.widget.model.WidgetConfig
import com.gekkotron.oikos.feature.widget.model.WidgetSnapshot
import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.Kind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WidgetStoresTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun fresh(name: String): File = File(tmp.root, name)

    @Test
    fun config_round_trip_remove_and_union() = runTest {
        val store = FileWidgetConfigStore.create(fresh("c.json"), backgroundScope)
        store.put(1, WidgetConfig(listOf("a", "b"), AlarmMode.HOME))
        store.put(2, WidgetConfig(listOf("b", "c")))
        assertEquals(WidgetConfig(listOf("a", "b"), AlarmMode.HOME), store.get(1))
        assertEquals(setOf("a", "b", "c"), store.allIds())
        store.remove(intArrayOf(1))
        assertNull(store.get(1))
        assertEquals(setOf("b", "c"), store.allIds())
    }

    @Test
    fun config_is_written_to_its_file() = runTest {
        val file = fresh("c2.json")
        FileWidgetConfigStore.create(file, backgroundScope).put(7, WidgetConfig(listOf("x")))
        assertTrue(file.readText().contains("\"x\""))
    }

    @Test
    fun snapshot_update_and_clear() = runTest {
        val store = FileWidgetSnapshotStore.create(fresh("s.json"), backgroundScope)
        val tile = TileSnapshot("a", "A", Kind.SWITCH, TileIcon.SWITCH, TileValue.OnOff(true))
        store.update { it.copy(tiles = mapOf("a" to tile), link = LinkStatus.FRESH, lastFresh = 5) }
        assertEquals(tile, store.current().tiles["a"])
        assertEquals(LinkStatus.FRESH, store.snapshot.first().link)
        store.clear()
        assertEquals(WidgetSnapshot(), store.current())
    }

    @Test
    fun corrupt_snapshot_file_falls_back_to_empty() = runTest {
        val file = fresh("bad.json").apply { writeText("{not json") }
        val store = FileWidgetSnapshotStore.create(file, backgroundScope)
        assertEquals(WidgetSnapshot(), store.current())
    }

    @Test
    fun snapshot_json_never_contains_a_code_field() = runTest {
        val file = fresh("s2.json")
        val store = FileWidgetSnapshotStore.create(file, backgroundScope)
        store.update {
            it.copy(tiles = mapOf("a" to TileSnapshot("a", "A", Kind.ALARM_PANEL, TileIcon.ALARM, TileValue.None, codeDisarmRequired = true)))
        }
        assertFalse(Regex("\"code\"\\s*:").containsMatchIn(file.readText()))
    }

    @Test
    fun set_up_again_leaves_set_up_oikos_not_dashes() = runTest {
        val store = FileWidgetSnapshotStore.create(fresh("s3.json"), backgroundScope)
        store.update { it.copy(tiles = mapOf("a" to TileSnapshot("a", "A", Kind.SWITCH, TileIcon.SWITCH, TileValue.OnOff(true))), link = LinkStatus.FRESH) }
        store.resetForSetUp()
        assertEquals(WidgetSnapshot(link = LinkStatus.NOT_CONFIGURED), store.current())
    }
}
