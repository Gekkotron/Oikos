package com.gekkotron.oikos.push

import android.app.NotificationManager
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.AlertChannel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(AndroidJUnit4::class)
class AlertChannelsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun `ensure creates the seven channels with their importance`() {
        AlertChannels(context).ensure()
        val expected = mapOf(
            "alarm" to NotificationManager.IMPORTANCE_HIGH,
            "security" to NotificationManager.IMPORTANCE_HIGH,
            "camera" to NotificationManager.IMPORTANCE_HIGH,
            "energy" to NotificationManager.IMPORTANCE_DEFAULT,
            "climate" to NotificationManager.IMPORTANCE_DEFAULT,
            "system" to NotificationManager.IMPORTANCE_DEFAULT,
            "misc" to NotificationManager.IMPORTANCE_DEFAULT,
        )
        assertEquals(expected.keys, manager.notificationChannels.map { it.id }.toSet())
        expected.forEach { (id, importance) -> assertEquals(importance, manager.getNotificationChannel(id).importance, id) }
    }

    @Test
    fun `names and descriptions come from resources`() {
        AlertChannels(context).ensure()
        assertEquals("Alarm", manager.getNotificationChannel("alarm").name.toString())
        assertNotNull(manager.getNotificationChannel("camera").description)
        assertEquals("Other", manager.getNotificationChannel("misc").name.toString())
    }

    @Test
    fun `ensure is safe to run on every launch`() {
        AlertChannels(context).ensure()
        AlertChannels(context).ensure()
        assertEquals(7, NotificationManagerCompat.from(context).notificationChannels.size)
    }

    @Test
    fun `every channel has its own name, description and an opaque accent`() {
        val names = AlertChannel.entries.map { AlertChannels.nameRes(it) }
        assertEquals(names.size, names.toSet().size)
        AlertChannel.entries.forEach { assertEquals(0xFF, AlertChannels.accent(it) ushr 24, it.id) }
    }
}
