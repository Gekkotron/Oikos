package com.gekkotron.oikos.feature.alerts.presentation

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.AlertChannel
import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.db.AlertDatabase
import com.gekkotron.oikos.alerts.demo.DemoAlerts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AlertsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private lateinit var db: AlertDatabase
    private lateinit var repo: AlertRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AlertDatabase::class.java).allowMainThreadQueries().build()
        repo = AlertRepository(db.alerts()) { now }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private suspend fun seed() = DemoAlerts.all(now).forEach { repo.upsert(it) }

    @Test
    fun `all alerts are grouped by day, and the screen is no longer loading`(): Unit = runTest(dispatcher) {
        seed()
        val vm = AlertsViewModel(repo, { now }, ZoneOffset.UTC)
        backgroundScope.launch { vm.state.collect {} }
        val ui = vm.state.first { !it.loading }
        assertEquals(null, ui.filter)
        assertEquals(6, ui.sections.sumOf { it.alerts.size })
    }

    @Test
    fun `a filter shows only its channel`(): Unit = runTest(dispatcher) {
        seed()
        val vm = AlertsViewModel(repo, { now }, ZoneOffset.UTC)
        backgroundScope.launch { vm.state.collect {} }
        vm.select(AlertChannel.CAMERA)
        val ui = vm.state.first { it.filter == AlertChannel.CAMERA && !it.loading }
        assertEquals(listOf("demo-camera"), ui.sections.flatMap { it.alerts }.map { it.id })
        vm.select(null)
        assertEquals(6, vm.state.first { it.filter == null && !it.loading }.sections.sumOf { it.alerts.size })
    }

    @Test
    fun `an empty filter has no sections`(): Unit = runTest(dispatcher) {
        seed()
        val vm = AlertsViewModel(repo, { now }, ZoneOffset.UTC)
        backgroundScope.launch { vm.state.collect {} }
        vm.select(AlertChannel.MISC)
        assertTrue(vm.state.first { it.filter == AlertChannel.MISC && !it.loading }.sections.isEmpty())
    }

    @Test
    fun `clear all empties the list`(): Unit = runTest(dispatcher) {
        seed()
        val vm = AlertsViewModel(repo, { now }, ZoneOffset.UTC)
        backgroundScope.launch { vm.state.collect {} }
        vm.clearAll()
        assertTrue(vm.state.first { it.sections.isEmpty() && !it.loading }.sections.isEmpty())
    }
}
