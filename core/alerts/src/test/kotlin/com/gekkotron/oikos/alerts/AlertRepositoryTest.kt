package com.gekkotron.oikos.alerts

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.db.AlertDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AlertRepositoryTest {
    private lateinit var db: AlertDatabase
    private var now = Instant.parse("2026-10-07T10:00:00Z")
    private lateinit var repo: AlertRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AlertDatabase::class.java).allowMainThreadQueries().build()
        repo = AlertRepository(db.alerts()) { now }
    }

    @After
    fun tearDown() = db.close()

    private fun alert(id: String, channel: AlertChannel = AlertChannel.CAMERA, sent: Instant = now) = Alert(
        id = id, channel = channel, severity = Severity.WARNING, title = "Title $id", body = "Body", imageUrl = "http://h/i.jpg",
        videoUrl = "http://h/c.mp4", entityId = "binary_sensor.door", actions = listOf(AlertAction("disarm", "Disarm")), source = "frigate",
        silent = true, resolved = false, sentAt = sent, receivedAt = sent,
    )

    @Test
    fun `an alert survives the round trip through Room`() = runBlocking {
        val original = alert("a")
        repo.upsert(original)
        assertEquals(original, repo.byId("a").first())
    }

    @Test
    fun `recent keeps the requested channels`() = runBlocking {
        repo.upsert(alert("cam", AlertChannel.CAMERA, now))
        repo.upsert(alert("nrg", AlertChannel.ENERGY, now.plusSeconds(1)))
        assertEquals(listOf("cam"), repo.recent(setOf(AlertChannel.ALARM, AlertChannel.SECURITY, AlertChannel.CAMERA)).first().map { it.id })
    }

    @Test
    fun `clearAll uses the clock for its marker`() = runBlocking {
        repo.upsert(alert("a", sent = now.minusSeconds(60)))
        repo.clearAll()
        assertTrue(repo.all(null).first().isEmpty())
        assertEquals(false, repo.insertIfMissing(alert("a", sent = now.minusSeconds(60))))
        assertEquals(true, repo.insertIfMissing(alert("b", sent = now.plusSeconds(1))))
    }

    @Test
    fun `markResolved shows in the flow`() = runBlocking {
        repo.upsert(alert("a"))
        repo.markResolved("a")
        assertTrue(repo.byId("a").first()!!.resolved)
    }
}
