package com.gekkotron.oikos.feature.security.presentation

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class SinceFormatTest {
    private val now = Instant.parse("2026-10-14T10:00:00Z")
    private fun ago(seconds: Long) = SinceFormat.span(Since.At(now.minusSeconds(seconds)), now)

    @Test
    fun `boundaries round down`() {
        assertEquals(SinceSpan.UnderAMinute, ago(59))
        assertEquals(SinceSpan.Minutes(1), ago(60))
        assertEquals(SinceSpan.Minutes(59), ago(3_599))
        assertEquals(SinceSpan.Hours(1), ago(3_600))
        assertEquals(SinceSpan.Hours(47), ago(48 * 3_600 - 1))
        assertEquals(SinceSpan.Days(2), ago(48 * 3_600))
        assertEquals(SinceSpan.Days(29), ago(29 * 86_400 + 5))
    }

    @Test
    fun `a time ahead of the phone clock reads as just now`() = assertEquals(SinceSpan.UnderAMinute, ago(-120))

    @Test
    fun `over the window`() = assertEquals(SinceSpan.Over(30), SinceFormat.span(Since.Over(Duration.ofDays(30)), now))
}
