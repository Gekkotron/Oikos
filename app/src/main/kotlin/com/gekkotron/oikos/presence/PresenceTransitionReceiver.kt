package com.gekkotron.oikos.presence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/** Catches ENTER / EXIT from the geofence client and enqueues a one-shot [PresenceWorker]. */
class PresenceTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val state = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> PresenceState.HOME
            Geofence.GEOFENCE_TRANSITION_EXIT -> PresenceState.AWAY
            else -> return
        }
        PresenceWork.enqueue(context, state)
    }

    companion object {
        const val ACTION = "com.gekkotron.oikos.presence.TRANSITION"
    }
}
