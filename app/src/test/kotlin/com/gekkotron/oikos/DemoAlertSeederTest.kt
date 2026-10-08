package com.gekkotron.oikos

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.db.AlertDatabase
import com.gekkotron.oikos.alerts.demo.DemoAlerts
import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.data.ProfileState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DemoAlertSeederTest {
    private lateinit var db: AlertDatabase
    private lateinit var repo: AlertRepository
    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private val demo = ProfileState.Ready(ConnectionProfile.Demo)
    private val direct = ProfileState.Ready(ConnectionProfile.Direct(LocalBroker("192.168.1.91")))

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AlertDatabase::class.java).allowMainThreadQueries().build()
        repo = AlertRepository(db.alerts()) { now }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun ids() = repo.all(null).first().map { it.id }.sorted()

    private val demoIds = DemoAlerts.all(now).map { it.id }.sorted()

    /** Room writes run on its own executor, in real time: poll on a real dispatcher, not under virtual time. */
    private suspend fun awaitIds(expected: List<String>, step: String) = withContext(Dispatchers.Default) {
        var seen: List<String> = emptyList()
        val done = withTimeoutOrNull(5_000) {
            while (true) {
                seen = ids()
                if (seen == expected) break
                delay(20)
            }
        }
        // A bare timeout says nothing in CI's console: name the step and what Room held.
        if (done == null) throw AssertionError("$step: after 5 s Room held $seen, expected $expected")
    }

    private suspend fun settle() = withContext(Dispatchers.Default) { delay(300) }

    @Test
    fun `entering the demo seeds its six alerts`(): Unit = runTest(UnconfinedTestDispatcher()) {
        val states = MutableStateFlow<ProfileState>(ProfileState.None)
        DemoAlertSeeder(states, repo, { now }, backgroundScope).start()
        assertTrue(ids().isEmpty())
        states.value = demo
        awaitIds(demoIds, "first seed")
    }

    @Test
    fun `a real house never gets demo alerts`(): Unit = runTest(UnconfinedTestDispatcher()) {
        val states = MutableStateFlow<ProfileState>(direct)
        DemoAlertSeeder(states, repo, { now }, backgroundScope).start()
        states.value = ProfileState.Locked
        settle()
        assertTrue(ids().isEmpty())
    }

    @Test
    fun `starting twice seeds once and leftovers are replaced, not added to`(): Unit = runTest(UnconfinedTestDispatcher()) {
        val states = MutableStateFlow<ProfileState>(demo)
        val seeder = DemoAlertSeeder(states, repo, { now }, backgroundScope)
        seeder.start()
        seeder.start()
        awaitIds(demoIds, "seed after two starts")
        repo.upsert(DemoAlerts.all(now)[0].copy(id = "stray"))
        states.value = ProfileState.None
        settle() // StateFlow conflates: let the seeder see the house go before the demo comes back
        states.value = demo
        awaitIds(demoIds, "reseed after leaving and re-entering the demo")
    }
}
