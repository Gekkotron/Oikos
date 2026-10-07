package com.gekkotron.oikos.alerts.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AlertDaoTest {
    private lateinit var db: AlertDatabase
    private lateinit var dao: AlertDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AlertDatabase::class.java).allowMainThreadQueries().build()
        dao = db.alerts()
    }

    @After
    fun tearDown() = db.close()

    private fun entity(id: String, sentAt: Long = 1_000, receivedAt: Long = sentAt, channel: String = "camera", title: String = "t-$id") = AlertEntity(
        id = id, channel = channel, severity = "info", title = title, body = "", imageUrl = null, videoUrl = null,
        entityId = null, actionsJson = "[]", source = null, silent = false, resolved = false, sentAt = sentAt, receivedAt = receivedAt,
    )

    @Test
    fun `upsert replaces the content but keeps the original receivedAt`() = runBlocking {
        dao.upsert(entity("a", sentAt = 1_000, receivedAt = 1_500, title = "first"))
        dao.upsert(entity("a", sentAt = 2_000, receivedAt = 2_500, title = "second"))
        val row = dao.byId("a").first()!!
        assertEquals("second", row.title)
        assertEquals(2_000, row.sentAt)
        assertEquals(1_500, row.receivedAt)
    }

    @Test
    fun `upsert trims to 200 rows, deleting the oldest by sentAt`() = runBlocking {
        for (i in 1..205) dao.upsert(entity("a$i", sentAt = i * 1_000L))
        val ids = dao.all(null).first().map { it.id }
        assertEquals(200, ids.size)
        assertFalse("a1" in ids)
        assertFalse("a5" in ids)
        assertTrue("a6" in ids)
        assertTrue("a205" in ids)
    }

    @Test
    fun `markResolved flags the row and ignores an unknown id`() = runBlocking {
        dao.upsert(entity("a"))
        dao.markResolved("a")
        dao.markResolved("ghost")
        assertTrue(dao.byId("a").first()!!.resolved)
        assertNull(dao.byId("ghost").first())
    }

    @Test
    fun `recent filters by channel and returns the newest first, limited`() = runBlocking {
        dao.upsert(entity("old", receivedAt = 1_000, channel = "alarm"))
        dao.upsert(entity("mid", receivedAt = 2_000, channel = "camera"))
        dao.upsert(entity("energy", receivedAt = 2_500, channel = "energy"))
        dao.upsert(entity("new", receivedAt = 3_000, channel = "security"))
        assertEquals(listOf("new", "mid"), dao.recent(listOf("alarm", "security", "camera"), 2).first().map { it.id })
    }

    @Test
    fun `all with a null channel returns every channel, with a channel only that one`() = runBlocking {
        dao.upsert(entity("a", receivedAt = 1_000, channel = "alarm"))
        dao.upsert(entity("b", receivedAt = 2_000, channel = "misc"))
        assertEquals(listOf("b", "a"), dao.all(null).first().map { it.id })
        assertEquals(listOf("a"), dao.all("alarm").first().map { it.id })
    }

    @Test
    fun `insertIfMissing inserts a new alert and leaves an existing one alone`() = runBlocking {
        assertTrue(dao.insertIfMissing(entity("a", title = "first")))
        assertFalse(dao.insertIfMissing(entity("a", title = "second")))
        assertEquals("first", dao.byId("a").first()!!.title)
    }

    @Test
    fun `insertIfMissing answers false when the trim deleted the alert it just inserted`() = runBlocking {
        for (i in 1..200) dao.upsert(entity("n$i", sentAt = 10_000L + i))
        assertFalse(dao.insertIfMissing(entity("ancient", sentAt = 5)))
        assertNull(dao.byId("ancient").first())
        assertEquals(200, dao.all(null).first().size)
    }

    @Test
    fun `clearAll deletes everything and refuses alerts sent before it`() = runBlocking {
        dao.upsert(entity("a", sentAt = 1_000))
        dao.clearAll(now = 5_000)
        assertTrue(dao.all(null).first().isEmpty())
        assertFalse(dao.insertIfMissing(entity("a", sentAt = 1_000)))
        assertFalse(dao.insertIfMissing(entity("edge", sentAt = 5_000)))
        assertTrue(dao.insertIfMissing(entity("fresh", sentAt = 5_001)))
    }

    @Test
    fun `reset deletes everything and forgets the clear marker`() = runBlocking<Unit> {
        dao.clearAll(now = 5_000)
        dao.upsert(entity("x", sentAt = 6_000))
        dao.reset()
        assertTrue(dao.all(null).first().isEmpty())
        assertTrue(dao.insertIfMissing(entity("old", sentAt = 1_000)))
        assertNotNull(dao.byId("old").first())
    }
}
