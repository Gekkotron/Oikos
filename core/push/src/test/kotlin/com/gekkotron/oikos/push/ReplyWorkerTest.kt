package com.gekkotron.oikos.push

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ReplyWorkerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val publisher = RecordingPublisher()
    private val targets = FakeTargets()
    private val input: Data = workDataOf(
        ReplyWorker.KEY_ALERT to "frigate-1", ReplyWorker.KEY_ACTION to "disarm", ReplyWorker.KEY_LABEL to "Disarm", ReplyWorker.KEY_TS to 1_790_000_000L,
    )

    private fun run(attempt: Int = 0, data: Data = input, pub: TopicPublisher = publisher): Result = runBlocking {
        AlertChannels(context).ensure()
        val registrar = PushRegistrar(targets, FakeFcmTokens(), pub, RegistrationStore.create(tmp.newFile().also { it.delete() }, CoroutineScope(Dispatchers.Unconfined)), FakeQueue(), { PushRegistrar.DeviceInfo("p", "en") })
        val factory = PushWorkerFactory(registrar, pub, targets, ReplyFailureNotifier(context))
        TestListenableWorkerBuilder<ReplyWorker>(context).setWorkerFactory(factory).setInputData(data).setRunAttemptCount(attempt).build().doWork()
    }

    @Test
    fun `it publishes the answer to the responses topic, not retained`() {
        assertEquals(Result.success(), run())
        val sent = publisher.sent.single()
        assertEquals("oikos/push/responses", sent.topic)
        assertFalse(sent.retain)
        val json = Json.parseToJsonElement(sent.payload).jsonObject
        assertEquals("frigate-1", json["id"]!!.jsonPrimitive.content)
        assertEquals("disarm", json["action"]!!.jsonPrimitive.content)
        assertEquals("install-1", json["install_id"]!!.jsonPrimitive.content)
        assertEquals(1_790_000_000L, json["ts"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `a failing publish is retried for the first four attempts`() {
        publisher.result = kotlin.Result.failure(IOException("offline"))
        for (attempt in 0..3) assertEquals(Result.retry(), run(attempt), "attempt ${attempt + 1}")
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `the fifth failure posts Couldn't send on system and gives up`() {
        publisher.result = kotlin.Result.failure(IOException("offline"))
        assertEquals(Result.failure(), run(attempt = 4))
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals("system", notification.channelId)
        assertEquals("Couldn't send Disarm", notification.extras.getString(android.app.Notification.EXTRA_TITLE))
    }

    @Test
    fun `a publisher that throws counts as a failed attempt, never escapes`() {
        val throwing = TopicPublisher { _, _, _ -> throw IllegalStateException("boom") }
        for (attempt in 0..3) assertEquals(Result.retry(), run(attempt, pub = throwing), "attempt ${attempt + 1}")
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertEquals(Result.failure(), run(attempt = 4, pub = throwing))
        assertEquals("Couldn't send Disarm", shadowOf(manager).allNotifications.single().extras.getString(android.app.Notification.EXTRA_TITLE))
    }

    @Test
    fun `no profile or the demo finishes without a retry or a notification`() {
        publisher.result = kotlin.Result.failure(NotConfigured())
        assertEquals(Result.success(), run(attempt = 4))
        targets.target = PushTarget.Demo
        assertEquals(Result.success(), run())
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `missing input fails without publishing`() {
        assertEquals(Result.failure(), run(data = Data.EMPTY))
        assertNull(publisher.sent.firstOrNull())
    }
}
