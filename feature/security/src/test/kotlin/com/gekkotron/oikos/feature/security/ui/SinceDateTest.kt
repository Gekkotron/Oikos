package com.gekkotron.oikos.feature.security.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SinceDateTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val now = Instant.parse("2026-10-14T16:00:00Z") // Wednesday 18:00 in Paris

    @Test
    fun `today shows the time only`() =
        assertEquals("08:42", sinceDate(Instant.parse("2026-10-14T06:42:00Z"), now, paris, Locale.UK, is24h = true))

    @Test
    fun `this week shows the weekday`() {
        val s = sinceDate(Instant.parse("2026-10-13T06:42:00Z"), now, paris, Locale.UK, is24h = true)
        assertTrue(s.contains("Tue") && s.contains("08:42"), s)
    }

    @Test
    fun `older shows the date`() {
        val s = sinceDate(Instant.parse("2026-10-03T06:42:00Z"), now, paris, Locale.UK, is24h = true)
        assertTrue(s.contains("3") && s.contains("Oct") && s.contains("08:42"), s)
    }

    @Test
    fun `12-hour clocks get a 12-hour time`() {
        val s = sinceDate(Instant.parse("2026-10-14T12:42:00Z"), now, paris, Locale.US, is24h = false)
        assertTrue(s.contains("2:42") && s.uppercase().contains("PM"), s)
    }
}
