package com.gekkotron.oikos.feature.alerts.presentation

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.db.AlertDatabase
import com.gekkotron.oikos.alerts.demo.DemoAlerts
import com.gekkotron.oikos.alerts.sync.HistorySync
import com.gekkotron.oikos.feature.alerts.FakePushStatusSource
import com.gekkotron.oikos.push.PushStatus
import com.gekkotron.oikos.transport.MqttMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class RecentAlertsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private lateinit var db: AlertDatabase
    private lateinit var repo: AlertRepository
    private val status = FakePushStatusSource()
    private val history by lazy { HistorySync(MutableSharedFlow<MqttMessage>(), repo) }

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

    @Test
    fun `only alarm, security and camera alerts show, newest first, at most five`(): Unit = runTest(dispatcher) {
        DemoAlerts.all(now).forEach { repo.upsert(it) }
        val vm = RecentAlertsViewModel(repo, status, history)
        backgroundScope.launch { vm.state.collect {} }
        val ui = vm.state.first { it.alerts.isNotEmpty() }
        assertEquals(listOf("demo-security", "demo-camera", "demo-alarm"), ui.alerts.map { it.id })
    }

    @Test
    fun `the banner follows notifications being off`(): Unit = runTest(dispatcher) {
        val vm = RecentAlertsViewModel(repo, status, history)
        backgroundScope.launch { vm.state.collect {} }
        assertEquals(false, vm.state.value.notificationsOff)
        status.status.value = PushStatus.NotificationsOff
        assertTrue(vm.state.first { it.notificationsOff }.notificationsOff)
        status.status.value = PushStatus.Registered(now)
        assertEquals(false, vm.state.first { !it.notificationsOff }.notificationsOff)
    }

    @Test
    fun `the catch-up count comes from the history sync and is acknowledged once shown`(): Unit = runTest(dispatcher) {
        val vm = RecentAlertsViewModel(repo, status, history)
        history.handle("""[{"v":1,"id":"x","title":"X","sent_at":1790000000}]""")
        assertEquals(1, vm.catchUp.value)
        vm.acknowledgeCatchUp()
        assertEquals(0, vm.catchUp.value)
    }

    @Test
    fun `refreshing the permission asks the status source`(): Unit = runTest(dispatcher) {
        RecentAlertsViewModel(repo, status, history).refreshPermission()
        assertEquals(1, status.refreshes)
    }
}
