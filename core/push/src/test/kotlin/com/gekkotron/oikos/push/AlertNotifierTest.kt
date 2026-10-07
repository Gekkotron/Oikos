package com.gekkotron.oikos.push

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.AlertAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AlertNotifierTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val picture: Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    private var allowed = true

    @Before
    fun setUp() {
        AlertChannels(context).ensure()
    }

    private fun notifier(images: NotificationImages = NotificationImages { picture }) =
        AlertNotifier(context, images, canPost = { allowed })

    private fun posted(tag: String): Notification? = shadowOf(manager).getNotification(tag, NotificationIds.ALERT)

    private fun Notification.template() = extras.getString(Notification.EXTRA_TEMPLATE)

    private fun Notification.title() = extras.getString(Notification.EXTRA_TITLE)

    @Test
    fun `a text alert posts on its channel with the id as tag and a big text style`(): Unit = runTest {
        assertTrue(notifier().post(testAlert(channel = com.gekkotron.oikos.alerts.AlertChannel.ALARM)))
        val n = posted("frigate-1")!!
        assertEquals("alarm", n.channelId)
        assertEquals("Front door", n.title())
        assertEquals("Person detected", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("android.app.Notification\$BigTextStyle", n.template())
    }

    @Test
    fun `a picture gives a big picture style and a large icon`(): Unit = runTest {
        assertTrue(notifier().post(testAlert(imageUrl = "http://192.168.1.20:5000/s.jpg")))
        val n = posted("frigate-1")!!
        assertEquals("android.app.Notification\$BigPictureStyle", n.template())
        assertNotNull(n.getLargeIcon())
    }

    @Test
    fun `a failed picture falls back to text only`(): Unit = runTest {
        assertTrue(notifier(NotificationImages { null }).post(testAlert(imageUrl = "http://h/s.jpg")))
        assertEquals("android.app.Notification\$BigTextStyle", posted("frigate-1")!!.template())
    }

    @Test
    fun `a picture that hangs gets 8 seconds in total, then the text notification is posted`(): Unit = runTest {
        val notifier = notifier(NotificationImages { awaitCancellation() })
        val result = async { notifier.post(testAlert(imageUrl = "http://h/s.jpg")) }
        advanceTimeBy(7_999)
        runCurrent()
        assertFalse(result.isCompleted)
        assertNull(posted("frigate-1"))
        advanceTimeBy(1)
        runCurrent()
        assertTrue(result.await())
        assertEquals("android.app.Notification\$BigTextStyle", posted("frigate-1")!!.template())
    }

    @Test
    fun `the budget bounds a slow chain of attempts, not each attempt`(): Unit = runTest {
        // A LAN attempt, a gateway attempt and a decode, 5 s each, are one budget of 8 s.
        val notifier = notifier(NotificationImages { delay(5_000); delay(5_000); delay(5_000); picture })
        val result = async { notifier.post(testAlert(imageUrl = "http://h/s.jpg")) }
        advanceTimeBy(8_000)
        runCurrent()
        assertTrue(result.await())
        assertEquals(8_000, testScheduler.currentTime)
        assertEquals("android.app.Notification\$BigTextStyle", posted("frigate-1")!!.template())
    }

    @Test
    fun `a picture that throws does not lose the alert`(): Unit = runTest {
        assertTrue(notifier(NotificationImages { error("decoder crashed") }).post(testAlert(imageUrl = "http://h/s.jpg")))
        assertNotNull(posted("frigate-1"))
    }

    @Test
    fun `at most three action buttons are shown`(): Unit = runTest {
        val actions = (1..4).map { AlertAction("a$it", "Action $it") }
        notifier().post(testAlert(actions = actions))
        assertEquals(listOf("Action 1", "Action 2", "Action 3"), posted("frigate-1")!!.actions.map { it.title.toString() })
    }

    @Test
    fun `tapping opens the alert deep link in this app`(): Unit = runTest {
        val notifier = notifier()
        val intent = shadowOf(notifier.openIntent("frigate-1")).savedIntent
        assertEquals("oikos://alert/frigate-1", intent.data.toString())
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(context.packageName, intent.`package`)
        notifier.post(testAlert())
        assertNotNull(posted("frigate-1")!!.contentIntent)
    }

    @Test
    fun `an id with path characters stays one segment of the deep link`(): Unit = runTest {
        val intent = shadowOf(notifier().openIntent("a/b c")).savedIntent
        assertEquals(listOf("a/b c"), intent.data!!.pathSegments)
    }

    @Test
    fun `two alerts with the same action id get distinct reply intents`(): Unit = runTest {
        val notifier = notifier()
        val disarm = AlertAction("disarm", "Disarm")
        val a = shadowOf(notifier.replyIntent("alert-a", disarm)).savedIntent
        val b = shadowOf(notifier.replyIntent("alert-b", disarm)).savedIntent
        // PendingIntent matching ignores extras: only action, data, type, class and categories keep two apart.
        assertFalse(a.filterEquals(b))
        assertNotEquals(a.data, b.data)
        assertEquals("alert-a", a.getStringExtra(ReplyReceiver.EXTRA_ALERT_ID))
        assertEquals("alert-b", b.getStringExtra(ReplyReceiver.EXTRA_ALERT_ID))
        assertEquals("disarm", a.getStringExtra(ReplyReceiver.EXTRA_ACTION_ID))
        assertEquals("Disarm", a.getStringExtra(ReplyReceiver.EXTRA_LABEL))
        assertEquals(ReplyReceiver.ACTION_REPLY, a.action)
        assertEquals(ReplyReceiver::class.java.name, a.component!!.className)
        // The same button of the same alert is the same PendingIntent, so a re-post updates instead of piling up.
        assertTrue(a.filterEquals(shadowOf(notifier.replyIntent("alert-a", disarm)).savedIntent))
        // Two different buttons of one alert differ too.
        assertFalse(a.filterEquals(shadowOf(notifier.replyIntent("alert-a", AlertAction("silence", "Silence"))).savedIntent))
    }

    @Test
    fun `a silent alert is posted without sound or vibration`(): Unit = runTest {
        notifier().post(testAlert(silent = true))
        assertEquals(NotificationCompat.GROUP_ALERT_SUMMARY, posted("frigate-1")!!.groupAlertBehavior)
    }

    @Test
    fun `when notifications are off nothing is fetched or posted`(): Unit = runTest {
        allowed = false
        var fetched = false
        val result = notifier(NotificationImages { fetched = true; picture }).post(testAlert(imageUrl = "http://h/s.jpg"))
        assertFalse(result)
        assertFalse(fetched)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `a later alert with the same id replaces the notification`(): Unit = runTest {
        val notifier = notifier()
        notifier.post(testAlert(title = "First"))
        notifier.post(testAlert(title = "Second"))
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertEquals("Second", posted("frigate-1")!!.title())
    }

    @Test
    fun `cancel removes the notification`(): Unit = runTest {
        val notifier = notifier()
        notifier.post(testAlert())
        notifier.cancel("frigate-1")
        assertNull(posted("frigate-1"))
    }

    @Test
    fun `a cancel that arrives while the picture loads is not undone by the late post`(): Unit = runTest {
        val gate = CompletableDeferred<Bitmap?>()
        val notifier = notifier(NotificationImages { gate.await() })
        val late = async { notifier.post(testAlert(imageUrl = "http://h/s.jpg")) }
        runCurrent()
        notifier.cancel("frigate-1")
        gate.complete(picture)
        assertFalse(late.await())
        assertNull(posted("frigate-1"))
    }

    @Test
    fun `an older alert finishing late does not replace a newer one with the same id`(): Unit = runTest {
        val gate = CompletableDeferred<Bitmap?>()
        var calls = 0
        val notifier = notifier(NotificationImages { if (calls++ == 0) gate.await() else null })
        val older = async { notifier.post(testAlert(title = "Older", imageUrl = "http://h/old.jpg")) }
        runCurrent()
        assertTrue(notifier.post(testAlert(title = "Newer", imageUrl = "http://h/new.jpg")))
        gate.complete(picture)
        assertFalse(older.await())
        assertEquals("Newer", posted("frigate-1")!!.title())
        assertEquals("android.app.Notification\$BigTextStyle", posted("frigate-1")!!.template())
    }

    @Test
    fun `an alert for another id is not affected by a cancel`(): Unit = runTest {
        val gate = CompletableDeferred<Bitmap?>()
        val notifier = notifier(NotificationImages { gate.await() })
        val other = async { notifier.post(testAlert(id = "other", imageUrl = "http://h/s.jpg")) }
        runCurrent()
        notifier.cancel("frigate-1")
        gate.complete(picture)
        assertTrue(other.await())
        assertNotNull(posted("other"))
    }
}
