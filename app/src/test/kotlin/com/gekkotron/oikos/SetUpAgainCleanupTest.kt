package com.gekkotron.oikos

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.disk.DiskCache
import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.db.AlertDatabase
import com.gekkotron.oikos.alerts.demo.DemoAlerts
import com.gekkotron.oikos.alerts.media.AlertImageCache
import com.gekkotron.oikos.alerts.media.Media
import com.gekkotron.oikos.alerts.media.MediaSource
import com.gekkotron.oikos.alerts.media.VideoCache
import com.gekkotron.oikos.push.FcmTokens
import com.gekkotron.oikos.push.PushRegistrar
import com.gekkotron.oikos.push.PushTarget
import com.gekkotron.oikos.push.PushTargets
import com.gekkotron.oikos.push.RegistrationQueue
import com.gekkotron.oikos.push.RegistrationStore
import com.gekkotron.oikos.push.ReplyQueue
import com.gekkotron.oikos.push.ReplyState
import com.gekkotron.oikos.push.TopicPublisher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SetUpAgainCleanupTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: AlertDatabase
    private lateinit var repo: AlertRepository
    private val events = mutableListOf<String>()
    private val now = Instant.parse("2026-10-07T12:00:00Z")

    private val targets = object : PushTargets {
        override suspend fun current(): PushTarget = PushTarget.Ready("install-1", "oikos-push/tokens/install-1")
        override val changes: Flow<PushTarget> get() = flowOf(PushTarget.Ready("install-1", "oikos-push/tokens/install-1"))
    }
    private val tokens = object : FcmTokens {
        override suspend fun token() = "token-1"
        override suspend fun delete() {
            events += "deleteToken"
        }
    }
    private val queue = object : RegistrationQueue {
        override fun enqueue() = Unit
        override fun cancel() {
            events += "cancelRegister"
        }

        override val active: Flow<Boolean> get() = flowOf(false)
    }
    private val replies = object : ReplyQueue {
        override fun enqueue(alertId: String, actionId: String, label: String): UUID = UUID.randomUUID()
        override fun state(id: UUID): Flow<ReplyState> = flowOf(ReplyState.PENDING)
        override fun cancelAll() {
            events += "cancelReplies"
        }
    }
    private val clips = object : MediaSource {
        override suspend fun bytes(url: String, maxBytes: Long, onProgress: (Long, Long?) -> Unit) = Result.success(Media(ByteArray(2), null))
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AlertDatabase::class.java).allowMainThreadQueries().build()
        repo = AlertRepository(db.alerts()) { now }
    }

    @After
    fun tearDown() = db.close()

    private fun cleanup(publisher: TopicPublisher, imageCache: AlertImageCache = newImageCache(), videos: VideoCache = VideoCache(tmp.newFolder(), clips)): SetUpAgainCleanup {
        val store = RegistrationStore.create(tmp.newFile().also { it.delete() }, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        val registrar = PushRegistrar(targets, tokens, publisher, store, queue, { PushRegistrar.DeviceInfo("p", "en") })
        return SetUpAgainCleanup(registrar, repo, replies, imageCache, videos) { events += "dismissNotifications" }
    }

    private fun newImageCache() = AlertImageCache(DiskCache.Builder().directory(tmp.newFolder().toOkioPath()).maxSizeBytes(1_000_000).build())

    @Test
    fun `it unregisters, drops the alerts, the caches, the replies and the notifications`(): Unit = runBlocking {
        DemoAlerts.all(now).forEach { repo.upsert(it) }
        repo.clearAll()
        DemoAlerts.all(now).forEach { repo.upsert(it) }
        val imageCache = newImageCache().also { it.put("http://h/s.jpg", byteArrayOf(1)) }
        val videoDir = tmp.newFolder("v")
        val videos = VideoCache(videoDir, clips).also { cache -> cache.file("a", "http://h/c.mp4").first { it is com.gekkotron.oikos.alerts.media.VideoState.Ready } }
        val sent = mutableListOf<Triple<String, String, Boolean>>()
        cleanup({ topic, payload, retain -> sent += Triple(topic, payload.decodeToString(), retain); Result.success(Unit) }, imageCache, videos).run()

        assertEquals(listOf(Triple("oikos-push/tokens/install-1", "", true)), sent)
        assertTrue(repo.all(null).first().isEmpty())
        assertNull(imageCache.open("http://h/s.jpg"))
        assertEquals(0, videoDir.listFiles()!!.size)
        assertTrue("cancelReplies" in events && "dismissNotifications" in events && "deleteToken" in events)
    }

    @Test
    fun `the unregister happens first, while the profile is still there to read`(): Unit = runBlocking {
        repo.upsert(DemoAlerts.all(now)[0])
        cleanup({ _, _, _ -> events += "publish"; Result.success(Unit) }).run()
        assertTrue(events.indexOf("cancelRegister") < events.indexOf("publish"))
        assertTrue(events.indexOf("publish") < events.indexOf("dismissNotifications"))
    }

    @Test
    fun `the clear marker is forgotten so a new house starts clean`(): Unit = runBlocking {
        repo.upsert(DemoAlerts.all(now)[0])
        repo.clearAll()
        cleanup({ _, _, _ -> Result.success(Unit) }).run()
        assertTrue(repo.insertIfMissing(DemoAlerts.all(now)[5]))
        assertNotNull(repo.byId("demo-system").first())
    }

    @Test
    fun `a failing step never stops the next ones`(): Unit = runBlocking {
        DemoAlerts.all(now).forEach { repo.upsert(it) }
        val throwing = object : FcmTokens {
            override suspend fun token(): String = throw IOException("no")
            override suspend fun delete(): Unit = throw IOException("no")
        }
        val store = RegistrationStore.create(tmp.newFile().also { it.delete() }, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        val registrar = PushRegistrar(targets, throwing, TopicPublisher { _, _, _ -> throw IOException("broker down") }, store, queue, { PushRegistrar.DeviceInfo("p", "en") })
        SetUpAgainCleanup(registrar, repo, replies, newImageCache(), VideoCache(tmp.newFolder(), clips)) { throw IllegalStateException("no notification service") }.run()
        assertTrue(repo.all(null).first().isEmpty())
        assertTrue("cancelReplies" in events)
    }

    @Test
    fun `the widgets are reset last, even after a failing step`(): Unit = runBlocking {
        val store = RegistrationStore.create(tmp.newFile().also { it.delete() }, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        val registrar = PushRegistrar(targets, tokens, TopicPublisher { _, _, _ -> Result.success(Unit) }, store, queue, { PushRegistrar.DeviceInfo("p", "en") })
        SetUpAgainCleanup(
            registrar, repo, replies, newImageCache(), VideoCache(tmp.newFolder(), clips),
            resetWidgets = { events += "resetWidgets" },
        ) { throw IllegalStateException("no notification service") }.run()
        assertEquals("resetWidgets", events.last())
    }
}
