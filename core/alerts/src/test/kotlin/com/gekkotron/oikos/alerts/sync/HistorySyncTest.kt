package com.gekkotron.oikos.alerts.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.alerts.AlertChannel
import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.Severity
import com.gekkotron.oikos.alerts.db.AlertDatabase
import com.gekkotron.oikos.transport.MqttMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class HistorySyncTest {
    private lateinit var db: AlertDatabase
    private lateinit var repo: AlertRepository
    private lateinit var sync: HistorySync
    private val messages = MutableSharedFlow<MqttMessage>(extraBufferCapacity = 16)
    private val logged = mutableListOf<String>()

    /** "Now" for the clear marker: after every alert below, so a clear covers them all. */
    private val clearTime = Instant.ofEpochSecond(1_790_001_000)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AlertDatabase::class.java).allowMainThreadQueries().build()
        repo = AlertRepository(db.alerts()) { clearTime }
        sync = HistorySync(messages, repo) { logged += it }
    }

    @After
    fun tearDown() { db.close() }

    private fun entry(id: String, sentAt: Long = 1_790_000_000, extra: String = "") =
        """{"v":1,"id":"$id","channel":"camera","severity":"info","title":"Title $id","body":"","silent":false,"sent_at":$sentAt$extra}"""

    private fun payload(vararg entries: String) = entries.joinToString(prefix = "[", postfix = "]", separator = ",")

    private fun existing(id: String, sent: Long = 1_790_000_000) = Alert(
        id, AlertChannel.CAMERA, Severity.INFO, "Stored $id", "", null, null, null, emptyList(), null, false, false,
        Instant.ofEpochSecond(sent), Instant.ofEpochSecond(sent),
    )

    @Test
    fun `only missing alerts are inserted and counted, existing ones are left as they are`(): Unit = runBlocking {
        repo.upsert(existing("a"))
        assertEquals(2, sync.handle(payload(entry("a"), entry("b", 1_790_000_001), entry("c", 1_790_000_002))))
        assertEquals("Stored a", repo.byId("a").first()!!.title)
        assertEquals("Title b", repo.byId("b").first()!!.title)
        assertEquals(2, sync.catchUp.value)
    }

    @Test
    fun `a replayed history adds nothing`(): Unit = runBlocking {
        val message = payload(entry("a"), entry("b", 1_790_000_001))
        assertEquals(2, sync.handle(message))
        assertEquals(0, sync.handle(message))
        assertEquals(2, sync.catchUp.value)
    }

    @Test
    fun `a cancel entry resolves an existing alert and arrives resolved when new`(): Unit = runBlocking {
        repo.upsert(existing("a"))
        sync.handle(payload(entry("a", extra = ""","cancel":true"""), entry("b", 1_790_000_001, extra = ""","cancel":true""")))
        assertTrue(repo.byId("a").first()!!.resolved)
        assertTrue(repo.byId("b").first()!!.resolved)
    }

    @Test
    fun `a cancel stored on its own for an unknown id never becomes a row`(): Unit = runBlocking {
        assertEquals(0, sync.handle("""[{"v":1,"id":"ghost","cancel":true,"sent_at":1790000000}]"""))
        assertNull(repo.byId("ghost").first())
        assertEquals(0, sync.catchUp.value)
    }

    @Test
    fun `bad entries are skipped one by one and a non array message whole`(): Unit = runBlocking {
        assertEquals(1, sync.handle("""[{"v":1,"title":"no id"},"junk",${entry("ok")},{"v":9,"id":"x","title":"t"}]"""))
        assertNotNull(repo.byId("ok").first())
        assertEquals(0, sync.handle("""{"not":"an array"}"""))
        assertEquals(0, sync.handle("garbage"))
        assertEquals(2, logged.size)
    }

    @Test
    fun `a cleared history is not resurrected by the retained history message`(): Unit = runBlocking {
        val message = payload(entry("a"), entry("b", 1_790_000_001))
        assertEquals(2, sync.handle(message))
        sync.acknowledge()
        repo.clearAll()
        // The broker redelivers the retained message on the next reconnect.
        assertEquals(0, sync.handle(message))
        assertTrue(repo.all(null).first().isEmpty())
        assertEquals(0, sync.catchUp.value)
        // An alert sent after the clear still arrives.
        assertEquals(1, sync.handle(payload(entry("a"), entry("fresh", clearTime.epochSecond + 5))))
        assertEquals(listOf("fresh"), repo.all(null).first().map { it.id })
    }

    @Test
    fun `an alert trimmed away in the same sync is not counted`(): Unit = runBlocking {
        for (i in 1..200) repo.upsert(existing("n$i", sent = 1_790_000_100L + i))
        assertEquals(0, sync.handle(payload(entry("ancient", sentAt = 1_000))))
        assertNull(repo.byId("ancient").first())
        assertEquals(0, sync.catchUp.value)
    }

    @Test
    fun `acknowledge resets the counter and a later sync starts again from zero`(): Unit = runBlocking {
        sync.handle(payload(entry("a")))
        assertEquals(1, sync.catchUp.value)
        sync.acknowledge()
        assertEquals(0, sync.catchUp.value)
        sync.handle(payload(entry("b", 1_790_000_001)))
        assertEquals(1, sync.catchUp.value)
    }

    @Test
    fun `the real OikosPush history payload is stored`(): Unit = runBlocking {
        val real = """[{"v":1,"id":"smoke-1","channel":"misc","severity":"info","title":"OikosPush smoke test","body":"Hello","actions":[],"silent":false,"sent_at":1791406022,"source":"smoke"}]"""
        assertEquals(1, sync.handle(real))
        assertEquals("OikosPush smoke test", repo.byId("smoke-1").first()!!.title)
    }

    @Test
    fun `start reads only the history topic`(): Unit = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        sync.start(scope)
        messages.emit(MqttMessage("homeassistant/switch/x/config", payload(entry("wrong")).encodeToByteArray(), retained = true))
        messages.emit(MqttMessage("oikos-push/history", payload(entry("right")).encodeToByteArray(), retained = true))
        // Room writes on its own executor: wait for the row instead of reading it the instant emit returns.
        assertNotNull(withTimeout(5_000) { repo.byId("right").first { it != null } })
        assertNull(repo.byId("wrong").first())
        scope.cancel()
    }

    @Test
    fun `a live non retained history message inserts the alert but does not count it`(): Unit = runBlocking {
        assertEquals(0, sync.handle(payload(entry("live")), retained = false))
        assertNotNull(repo.byId("live").first())
        assertEquals(0, sync.catchUp.value)
    }

    @Test
    fun `a retained history message counts what it inserts`(): Unit = runBlocking {
        assertEquals(1, sync.handle(payload(entry("old")), retained = true))
        assertEquals(1, sync.catchUp.value)
    }

    @Test
    fun `a message that makes the repository throw does not stop the next one`(): Unit = runBlocking {
        var calls = 0
        val flaky = object : AlertRepository(db.alerts()) {
            override suspend fun insertIfMissing(alert: Alert): Boolean {
                if (calls++ == 0) throw IllegalStateException("disk gone")
                return super.insertIfMissing(alert)
            }
        }
        val failing = HistorySync(messages, flaky) { logged += it }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        failing.start(scope)
        messages.emit(MqttMessage("oikos-push/history", payload(entry("boom")).encodeToByteArray(), retained = true))
        messages.emit(MqttMessage("oikos-push/history", payload(entry("fine")).encodeToByteArray(), retained = true))
        assertNotNull(withTimeout(5_000) { repo.byId("fine").first { it != null } })
        assertTrue(logged.any { it.contains("IllegalStateException") })
        assertTrue(logged.none { it.contains("disk") })
        scope.cancel()
    }
}
