package com.gekkotron.oikos.designsystem.permissions

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class NotificationEnablerTest {
    @Test
    fun `settings open only when the prompt was refused and the system will not ask again`() {
        assertTrue(shouldOpenSettings(granted = false, showRationale = false))
        assertFalse(shouldOpenSettings(granted = false, showRationale = true))
        assertFalse(shouldOpenSettings(granted = true, showRationale = false))
        assertFalse(shouldOpenSettings(granted = true, showRationale = true))
    }

    @Test
    fun `the app's notification settings open for this package`() {
        val app = RuntimeEnvironment.getApplication()
        openNotificationSettings(app)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }
}
