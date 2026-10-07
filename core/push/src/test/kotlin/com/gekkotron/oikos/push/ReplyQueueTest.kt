package com.gekkotron.oikos.push

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ReplyQueueTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `the request carries the alert, the action, the label and the tap time`() {
        val request = replyRequest("frigate-1", "disarm", "Disarm", ts = 1_790_000_000L)
        val data = request.workSpec.input
        assertEquals("frigate-1", data.getString(ReplyWorker.KEY_ALERT))
        assertEquals("disarm", data.getString(ReplyWorker.KEY_ACTION))
        assertEquals("Disarm", data.getString(ReplyWorker.KEY_LABEL))
        assertEquals(1_790_000_000L, data.getLong(ReplyWorker.KEY_TS, 0))
        assertTrue(ReplyWorker.TAG in request.tags)
        assertTrue(request.workSpec.expedited)
    }

    @Test
    fun `a retried reply keeps its ts and a double tap enqueues once`(): Unit = runBlocking {
        // Retries: the same input data (and so the same ts) is published on every attempt.
        val publisher = RecordingPublisher(result = Result.failure(IOException("offline")))
        val failures = ReplyFailureNotifier(context, canPost = { false })
        val registrar = PushRegistrar(FakeTargets(), FakeFcmTokens(), publisher, RegistrationStore.create(tmp.newFile().also { it.delete() }, CoroutineScope(Dispatchers.Unconfined)), FakeQueue(), { PushRegistrar.DeviceInfo("p", "en") })
        val factory = PushWorkerFactory(registrar, publisher, FakeTargets(), failures)
        val input = replyRequest("frigate-1", "disarm", "Disarm", ts = 1_790_000_000L).workSpec.input
        for (attempt in 0..2) {
            val result = TestListenableWorkerBuilder<ReplyWorker>(context).setWorkerFactory(factory).setInputData(input).setRunAttemptCount(attempt).build().doWork()
            assertEquals(ListenableWorker.Result.retry(), result)
        }
        assertEquals(1, publisher.sent.map { it.payload }.toSet().size)

        // Double tap: the second enqueue is KEEP, so one work item exists.
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val queue = WorkManagerReplyQueue({ WorkManager.getInstance(context) }, clock = { Instant.ofEpochSecond(1_790_000_000L) })
        val first = queue.enqueue("frigate-1", "disarm", "Disarm")
        val second = queue.enqueue("frigate-1", "disarm", "Disarm")
        assertNotEquals(first, second)
        assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork("reply-frigate-1-disarm").get().size)
        // Another button on the same alert is separate work.
        queue.enqueue("frigate-1", "silence", "Silence")
        assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork("reply-frigate-1-silence").get().size)
    }

    @Test
    fun `work states map to reply states`() {
        assertEquals(ReplyState.PENDING, null.toReplyState())
        assertEquals(ReplyState.PENDING, WorkInfo.State.ENQUEUED.toReplyState())
        assertEquals(ReplyState.PENDING, WorkInfo.State.RUNNING.toReplyState())
        assertEquals(ReplyState.SENT, WorkInfo.State.SUCCEEDED.toReplyState())
        assertEquals(ReplyState.FAILED, WorkInfo.State.FAILED.toReplyState())
        assertEquals(ReplyState.FAILED, WorkInfo.State.CANCELLED.toReplyState())
    }
}
