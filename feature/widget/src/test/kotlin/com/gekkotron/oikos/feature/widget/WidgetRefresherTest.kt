package com.gekkotron.oikos.feature.widget

import com.gekkotron.oikos.feature.widget.model.LinkStatus
import com.gekkotron.oikos.feature.widget.model.TileIcon
import com.gekkotron.oikos.feature.widget.model.TileSnapshot
import com.gekkotron.oikos.feature.widget.model.TileStatus
import com.gekkotron.oikos.feature.widget.model.TileValue
import com.gekkotron.oikos.feature.widget.model.WidgetCommand
import com.gekkotron.oikos.feature.widget.model.WidgetConfig
import com.gekkotron.oikos.feature.widget.model.WidgetSnapshot
import com.gekkotron.oikos.feature.widget.testing.MemConfigs
import com.gekkotron.oikos.feature.widget.testing.MemSnapshots
import com.gekkotron.oikos.model.Bindings
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant

class WidgetRefresherTest {
    private object B : Bindings

    private val sw = Entity("s", null, "Kettle", Kind.SWITCH, null, null, null, Capabilities(), B)
    private val kettleTile = TileSnapshot("s", "Kettle", Kind.SWITCH, TileIcon.SWITCH, TileValue.OnOff(true))

    private fun on(v: Boolean, t: Long = 100) = EntityState("s", StateValue.OnOff(v), lastUpdate = Instant.ofEpochMilli(t))

    private class FakeEntities(
        var refresh: RefreshResult = RefreshResult.Failed(Failure.OFFLINE),
        var send: suspend () -> SendResult = { SendResult.NotConfirmed },
    ) : WidgetEntities {
        val sent = mutableListOf<Pair<String, Command>>()
        override suspend fun refresh(ids: Set<String>) = refresh
        override suspend fun send(id: String, command: Command): SendResult {
            sent += id to command
            return send()
        }
        override suspend fun catalog(): CatalogResult = CatalogResult.Failed(Failure.OFFLINE)
    }

    private val now = 1_000L
    private var updates = 0
    private val configs = MemConfigs(mutableMapOf(1 to WidgetConfig(listOf("s"))))
    private val snaps = MemSnapshots()

    private fun refresher(e: WidgetEntities) = WidgetRefresher(e, configs, snaps, { updates++ }, { now })

    @Test
    fun refresh_ok_writes_tiles_and_fresh_link() = runTest {
        refresher(FakeEntities(RefreshResult.Ok(mapOf("s" to sw), mapOf("s" to on(true)), complete = true, historyEnabled = false))).refresh()
        assertEquals(TileValue.OnOff(true), snaps.flow.value.tiles["s"]?.value)
        assertEquals(LinkStatus.FRESH, snaps.flow.value.link)
        assertEquals(1_000L, snaps.flow.value.lastFresh)
        assertEquals(1, updates)
    }

    @Test
    fun refresh_failure_keeps_tiles_and_sets_link() = runTest {
        snaps.flow.value = WidgetSnapshot(tiles = mapOf("s" to kettleTile), link = LinkStatus.FRESH, lastFresh = 5)
        refresher(FakeEntities(RefreshResult.Failed(Failure.AUTH_FAILED))).refresh()
        assertEquals(TileValue.OnOff(true), snaps.flow.value.tiles["s"]?.value)
        assertEquals(LinkStatus.AUTH_FAILED, snaps.flow.value.link)
        assertEquals(5L, snaps.flow.value.lastFresh)
        assertEquals(1, updates)
    }

    @Test
    fun refresh_with_no_widgets_does_nothing() = runTest {
        configs.map.clear()
        refresher(FakeEntities()).refresh()
        assertEquals(0, updates)
    }

    @Test
    fun refresh_drops_tiles_no_widget_uses() = runTest {
        snaps.flow.value = WidgetSnapshot(tiles = mapOf("gone" to TileSnapshot("gone", "G", Kind.SWITCH, TileIcon.SWITCH, TileValue.None)))
        refresher(FakeEntities(RefreshResult.Ok(mapOf("s" to sw), mapOf("s" to on(true)), true, false))).refresh()
        assertEquals(setOf("s"), snaps.flow.value.tiles.keys)
    }

    @Test
    fun refresh_clears_a_failed_marker() = runTest {
        snaps.flow.value = WidgetSnapshot(tiles = mapOf("s" to kettleTile.copy(status = TileStatus.FAILED)))
        refresher(FakeEntities(RefreshResult.Ok(mapOf("s" to sw), mapOf("s" to on(true)), true, false))).refresh()
        assertEquals(TileStatus.OK, snaps.flow.value.tiles["s"]?.status)
    }

    @Test
    fun send_marks_pending_then_confirmed() = runTest {
        snaps.flow.value = WidgetSnapshot(tiles = mapOf("s" to kettleTile))
        val gate = CompletableDeferred<Unit>()
        val e = FakeEntities(send = {
            gate.await()
            SendResult.Confirmed(sw, on(false, 2_000), false)
        })
        val job = async { refresher(e).send("s", WidgetCommand.Toggle, null) }
        testScheduler.runCurrent()
        assertEquals(TileStatus.PENDING, snaps.flow.value.tiles["s"]?.status)
        assertEquals(1_000L, snaps.flow.value.tiles["s"]?.pendingSince)
        gate.complete(Unit)
        job.await()
        assertEquals(TileStatus.OK, snaps.flow.value.tiles["s"]?.status)
        assertEquals(TileValue.OnOff(false), snaps.flow.value.tiles["s"]?.value)
        assertEquals(listOf("s" to Command.Toggle), e.sent)
        assertEquals(2, updates)
    }

    @Test
    fun send_not_confirmed_marks_failed_and_keeps_value() = runTest {
        snaps.flow.value = WidgetSnapshot(tiles = mapOf("s" to kettleTile))
        refresher(FakeEntities()).send("s", WidgetCommand.Toggle, null)
        assertEquals(TileStatus.FAILED, snaps.flow.value.tiles["s"]?.status)
        assertEquals(TileValue.OnOff(true), snaps.flow.value.tiles["s"]?.value)
    }

    @Test
    fun send_failed_offline_also_sets_link() = runTest {
        snaps.flow.value = WidgetSnapshot(tiles = mapOf("s" to kettleTile))
        refresher(FakeEntities(send = { SendResult.Failed(Failure.OFFLINE) })).send("s", WidgetCommand.Toggle, null)
        assertEquals(TileStatus.FAILED, snaps.flow.value.tiles["s"]?.status)
        assertEquals(LinkStatus.OFFLINE, snaps.flow.value.link)
    }

    @Test
    fun disarm_code_reaches_the_command_but_never_the_snapshot() = runTest {
        snaps.flow.value = WidgetSnapshot(tiles = mapOf("a" to TileSnapshot("a", "Alarm", Kind.ALARM_PANEL, TileIcon.ALARM, TileValue.None)))
        val e = FakeEntities()
        refresher(e).send("a", WidgetCommand.Disarm, "1234")
        assertEquals(Command.Disarm("1234"), e.sent.single().second)
        assertFalse(snaps.flow.value.toString().contains("1234"))
    }
}
