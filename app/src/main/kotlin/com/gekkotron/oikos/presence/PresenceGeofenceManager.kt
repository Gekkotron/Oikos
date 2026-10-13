package com.gekkotron.oikos.presence

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import com.gekkotron.oikos.data.AppPreferences
import com.gekkotron.oikos.data.PresenceConfig

/**
 * Registers / unregisters the single home geofence to match the current [PresenceConfig]. Guarded by foreground location;
 * the UI gates background location separately before enabling the toggle.
 */
class PresenceGeofenceManager(
    private val context: Context,
    private val preferences: AppPreferences,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val client: GeofencingClient = LocationServices.getGeofencingClient(context),
) {
    /** Collects [AppPreferences.presence] and (re)applies the spec. Call once from Application.onCreate. */
    fun start() {
        scope.launch {
            preferences.presence.filterNotNull().collect { apply(it) }
        }
    }

    suspend fun apply(config: PresenceConfig) {
        val spec = PresenceGeofenceSpec.from(config)
        if (spec == null) {
            removeAll()
            return
        }
        if (!hasFineLocation()) return
        register(spec)
    }

    private suspend fun register(spec: PresenceGeofenceSpec) {
        val geofence = Geofence.Builder()
            .setRequestId(spec.requestId)
            .setCircularRegion(spec.lat, spec.lng, spec.radiusM.toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_EXIT)
            .addGeofence(geofence)
            .build()
        removeAll()
        try {
            client.addGeofences(request, pendingIntent()).await()
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call; the next AppPreferences change (or user re-enable) will retry.
        }
    }

    private suspend fun removeAll() {
        try {
            client.removeGeofences(pendingIntent()).await()
        } catch (_: Exception) {
            // Nothing to remove, or Play services unavailable; a no-op is fine.
        }
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, PresenceTransitionReceiver::class.java).setAction(PresenceTransitionReceiver.ACTION)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}
