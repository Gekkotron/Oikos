package com.gekkotron.oikos.feature.security.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.gekkotron.oikos.feature.security.R
import com.gekkotron.oikos.feature.security.presentation.SinceSpan
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "2 h", "<1 min", "30+ d": the tile's compact form. */
@Composable
internal fun sinceShort(span: SinceSpan): String = when (span) {
    SinceSpan.UnderAMinute -> stringResource(R.string.security_since_short_now)
    is SinceSpan.Minutes -> stringResource(R.string.security_since_short_minutes, span.n.toInt())
    is SinceSpan.Hours -> stringResource(R.string.security_since_short_hours, span.n.toInt())
    is SinceSpan.Days -> stringResource(R.string.security_since_short_days, span.n.toInt())
    is SinceSpan.Over -> stringResource(R.string.security_since_short_over, span.days.toInt())
}

/** "2 hours", "more than 30 days": what TalkBack reads. */
@Composable
internal fun sinceLong(span: SinceSpan): String = when (span) {
    SinceSpan.UnderAMinute -> stringResource(R.string.security_since_long_now)
    is SinceSpan.Minutes -> pluralStringResource(R.plurals.security_since_long_minutes, span.n.toInt(), span.n.toInt())
    is SinceSpan.Hours -> pluralStringResource(R.plurals.security_since_long_hours, span.n.toInt(), span.n.toInt())
    is SinceSpan.Days -> pluralStringResource(R.plurals.security_since_long_days, span.n.toInt(), span.n.toInt())
    is SinceSpan.Over -> pluralStringResource(R.plurals.security_since_long_over, span.days.toInt(), span.days.toInt())
}

/** "08:42" today, "Tue 08:42" within six days, "3 Oct, 08:42" before (locale patterns, last-change spec §4.5). */
internal fun sinceDate(at: Instant, now: Instant, zone: ZoneId, locale: Locale, is24h: Boolean): String {
    val day = at.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    val time = if (is24h) "Hm" else "hm"
    val skeleton = when {
        day == today -> time
        !day.isBefore(today.minusDays(6)) -> "EEE$time"
        else -> "dMMM$time"
    }
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(at.atZone(zone))
}
