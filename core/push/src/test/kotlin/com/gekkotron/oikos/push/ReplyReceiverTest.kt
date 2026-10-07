package com.gekkotron.oikos.push

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
@Config(application = TestPushApp::class)
class ReplyReceiverTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class RecordingReplies : ReplyQueue {
        val enqueued = mutableListOf<Triple<String, String, String>>()
        override fun enqueue(alertId: String, actionId: String, label: String): UUID {
            enqueued += Triple(alertId, actionId, label)
            return UUID.randomUUID()
        }

        override fun state(id: UUID): Flow<ReplyState> = flowOf(ReplyState.PENDING)

        override fun cancelAll() = Unit
    }

    private val app = RuntimeEnvironment.getApplication() as TestPushApp
    private val replies = RecordingReplies()
    private val manager = app.getSystemService(NotificationManager::class.java)

    private fun component() = object : PushComponent {
        override val registrar = PushRegistrar(
            FakeTargets(), FakeFcmTokens(), RecordingPublisher(),
            RegistrationStore.create(tmp.newFile().also { it.delete() }, CoroutineScope(Dispatchers.Unconfined)), FakeQueue(), { PushRegistrar.DeviceInfo("p", "en") },
        )
        override val replies: ReplyQueue = this@ReplyReceiverTest.replies
        override val ingestor: AlertIngestor get() = error("not used")
    }

    @Test
    fun `a tap dismisses the notification and enqueues the reply`() {
        app.component = component()
        AlertChannels(app).ensure()
        val posted = Notification.Builder(app, "alarm").setSmallIcon(android.R.drawable.ic_dialog_info).build()
        NotificationManagerCompat.from(app).notify("frigate-1", NotificationIds.ALERT, posted)
        assertEquals(1, shadowOf(manager).allNotifications.size)

        ReplyReceiver().onReceive(
            app,
            Intent(ReplyReceiver.ACTION_REPLY)
                .putExtra(ReplyReceiver.EXTRA_ALERT_ID, "frigate-1")
                .putExtra(ReplyReceiver.EXTRA_ACTION_ID, "disarm")
                .putExtra(ReplyReceiver.EXTRA_LABEL, "Disarm"),
        )

        assertNull(shadowOf(manager).getNotification("frigate-1", NotificationIds.ALERT))
        assertEquals(listOf(Triple("frigate-1", "disarm", "Disarm")), replies.enqueued)
    }

    @Test
    fun `an intent without an alert or action is ignored`() {
        app.component = component()
        ReplyReceiver().onReceive(app, Intent(ReplyReceiver.ACTION_REPLY).putExtra(ReplyReceiver.EXTRA_ALERT_ID, "frigate-1"))
        assertEquals(emptyList(), replies.enqueued)
    }

    @Test
    fun `the receiver class name matches its manifest entry`() {
        assertEquals("com.gekkotron.oikos.push.ReplyReceiver", ReplyReceiver::class.java.name)
    }
}
