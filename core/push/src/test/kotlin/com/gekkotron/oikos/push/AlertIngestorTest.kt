package com.gekkotron.oikos.push

import android.app.Notification
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.db.AlertDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AlertIngestorTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var db: AlertDatabase
    private lateinit var repo: AlertRepository
    private val shown = mutableMapOf<String, Notification>()
    private var allowed = true
    private val logged = mutableListOf<String>()
    private lateinit var ingestor: AlertIngestor

    @Before
    fun setUp() {
        AlertChannels(context).ensure()
        db = Room.inMemoryDatabaseBuilder(context, AlertDatabase::class.java).allowMainThreadQueries().build()
        repo = AlertRepository(db.alerts())
        val notifier = AlertNotifier(context, NotificationImages { null }, canPost = { allowed }, show = { tag, n -> shown[tag] = n })
        ingestor = AlertIngestor(repo, notifier) { logged += it }
    }

    @After
    fun tearDown() = db.close()

    private fun data(vararg extra: Pair<String, String>) = mapOf("v" to "1", "id" to "frigate-1", "title" to "Front door", "channel" to "camera") + extra

    @Test
    fun `a valid alert is stored and posted`(): Unit = runBlocking {
        assertEquals(AlertIngestor.Outcome.Shown("frigate-1"), ingestor.onMessage(data("body" to "Person")))
        assertEquals("Front door", repo.byId("frigate-1").first()!!.title)
        assertTrue("frigate-1" in shown)
    }

    @Test
    fun `malformed data is dropped, logged by key names only, and stores nothing`(): Unit = runBlocking {
        assertEquals(AlertIngestor.Outcome.Dropped, ingestor.onMessage(mapOf("v" to "2", "id" to "x", "title" to "Secret title")))
        assertTrue(repo.all(null).first().isEmpty())
        assertTrue(shown.isEmpty())
        assertTrue("Secret title" !in logged.single())
    }

    @Test
    fun `a cancel dismisses the notification and resolves the stored alert`(): Unit = runBlocking {
        ingestor.onMessage(data())
        assertEquals(AlertIngestor.Outcome.Cancelled("frigate-1"), ingestor.onMessage(mapOf("v" to "1", "id" to "frigate-1", "cancel" to "true")))
        assertTrue(repo.byId("frigate-1").first()!!.resolved)
    }

    @Test
    fun `a cancel for an unknown id stores nothing and a later alert with that id is not resolved`(): Unit = runBlocking {
        ingestor.onMessage(mapOf("v" to "1", "id" to "frigate-9", "cancel" to "true"))
        assertTrue(repo.all(null).first().isEmpty())
        ingestor.onMessage(data("id" to "frigate-9"))
        assertFalse(repo.byId("frigate-9").first()!!.resolved)
    }

    @Test
    fun `a re-sent alert after a cancel is shown again and no longer resolved`(): Unit = runBlocking {
        ingestor.onMessage(data())
        ingestor.onMessage(mapOf("v" to "1", "id" to "frigate-1", "cancel" to "true"))
        assertIs<AlertIngestor.Outcome.Shown>(ingestor.onMessage(data("title" to "Front door again")))
        val stored = repo.byId("frigate-1").first()!!
        assertFalse(stored.resolved)
        assertEquals("Front door again", stored.title)
    }

    @Test
    fun `with notifications off the alert is still stored`(): Unit = runBlocking {
        allowed = false
        assertEquals(AlertIngestor.Outcome.StoredOnly("frigate-1"), ingestor.onMessage(data()))
        assertEquals("Front door", repo.byId("frigate-1").first()!!.title)
        assertNull(shown["frigate-1"])
    }
}
