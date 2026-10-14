package com.gekkotron.oikos.presence

import android.content.Context
import com.gekkotron.oikos.push.PresenceRefresher

/** Satisfies [PresenceRefresher] by enqueueing a [PresenceRefreshWorker] one-shot. */
class WorkManagerPresenceRefresher(private val context: Context) : PresenceRefresher {
    override fun requestRefresh() {
        PresenceRefreshWork.enqueue(context)
    }
}
