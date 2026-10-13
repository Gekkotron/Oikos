package com.gekkotron.oikos.push

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.db.AlertDatabase
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(AndroidJUnit4::class)
@Config(application = TestPushApp::class)
class OikosMessagingServiceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val app = RuntimeEnvironment.getApplication() as TestPushApp
    private val queue = FakeQueue()
    private val refresher = CountingRefresher()

    private fun install(): AlertRepository {
        val db = Room.inMemoryDatabaseBuilder(app, AlertDatabase::class.java).allowMainThreadQueries().build()
        val repo = AlertRepository(db.alerts())
        val theIngestor = AlertIngestor(repo, AlertNotifier(app, NotificationImages { null }, canPost = { false }))
        val theRegistrar = PushRegistrar(
            FakeTargets(), FakeFcmTokens(), RecordingPublisher(),
            RegistrationStore.create(tmp.newFile().also { it.delete() }, CoroutineScope(Dispatchers.Unconfined)), queue, { PushRegistrar.DeviceInfo("p", "en") },
        )
        app.component = object : PushComponent {
            override val registrar = theRegistrar
            override val replies: ReplyQueue get() = error("not used")
            override val ingestor = theIngestor
            override val presenceRefresher = refresher
        }
        return repo
    }

    private class CountingRefresher : PresenceRefresher {
        var calls = 0
        override fun requestRefresh() { calls++ }
    }

    @Test
    fun `a data message is ingested`(): Unit = runBlocking {
        val repo = install()
        val service = Robolectric.buildService(OikosMessagingService::class.java).create().get()
        service.onMessageReceived(
            RemoteMessage.Builder("to").setData(mapOf("v" to "1", "id" to "frigate-1", "title" to "Front door", "channel" to "camera")).build(),
        )
        assertNotNull(repo.byId("frigate-1").first())
    }

    @Test
    fun `a malformed message does not crash the service`() {
        install()
        val service = Robolectric.buildService(OikosMessagingService::class.java).create().get()
        service.onMessageReceived(RemoteMessage.Builder("to").setData(mapOf("garbage" to "1")).build())
    }

    @Test
    fun `a new token asks for a registration`() {
        install()
        val service = Robolectric.buildService(OikosMessagingService::class.java).create().get()
        service.onNewToken("fresh-token")
        assertEquals(1, queue.enqueued)
    }

    @Test
    fun `a presence_refresh command triggers the refresher and skips alert ingestion`(): Unit = runBlocking {
        val repo = install()
        val service = Robolectric.buildService(OikosMessagingService::class.java).create().get()
        service.onMessageReceived(
            RemoteMessage.Builder("to").setData(mapOf("v" to "1", "cmd" to "presence_refresh")).build(),
        )
        assertEquals(1, refresher.calls)
        assertEquals(0, repo.all(channel = null).first().size, "a cmd message must not be stored as an alert")
    }
}
