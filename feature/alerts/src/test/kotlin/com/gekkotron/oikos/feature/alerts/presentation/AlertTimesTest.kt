package com.gekkotron.oikos.feature.alerts.presentation

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AlertTimesTest {
    private val context = RuntimeEnvironment.getApplication()
    private val now = Instant.parse("2026-10-07T12:00:00Z")

    @Test
    fun `under a minute is just now, also for a clock that is a little ahead`() {
        assertEquals("Just now", AlertTimes.relative(context, now.minusSeconds(30), now))
        assertEquals("Just now", AlertTimes.relative(context, now.plusSeconds(20), now))
    }

    @Test
    fun `minutes and hours are relative`() {
        assertTrue("2" in AlertTimes.relative(context, now.minusSeconds(120), now))
        assertTrue("3" in AlertTimes.relative(context, now.minusSeconds(3 * 3600L), now))
    }

    @Test
    @Config(qualifiers = "fr")
    fun `the words follow the locale`() {
        assertEquals("À l'instant", AlertTimes.relative(context, now.minusSeconds(5), now))
    }
}
