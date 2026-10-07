package com.gekkotron.oikos.feature.alerts.presentation

import android.content.Context
import android.text.format.DateUtils
import com.gekkotron.oikos.feature.alerts.R
import java.time.Duration
import java.time.Instant

object AlertTimes {
    /** "Just now" under a minute (or a little in the future), then the platform's localized "2 min. ago". */
    fun relative(context: Context, then: Instant, now: Instant): String {
        if (Duration.between(then, now).seconds < 60) return context.getString(R.string.alerts_just_now)
        return DateUtils.getRelativeTimeSpanString(
            then.toEpochMilli(), now.toEpochMilli(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
    }
}
