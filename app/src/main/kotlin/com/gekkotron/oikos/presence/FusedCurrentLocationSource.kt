package com.gekkotron.oikos.presence

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Fused Location Provider fresh fix. Returns null on missing permission, Play Services error, or no fix. */
class FusedCurrentLocationSource(private val context: Context) : CurrentLocationSource {
    private val client: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

    override suspend fun current(): Pair<Double, Double>? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val cancel = CancellationTokenSource()
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { cancel.cancel() }
            try {
                @Suppress("MissingPermission")
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancel.token)
                    .addOnSuccessListener { loc -> cont.resume(loc?.let { it.latitude to it.longitude }) }
                    .addOnFailureListener { cont.resume(null) }
                    .addOnCanceledListener { cont.resume(null) }
            } catch (_: SecurityException) {
                cont.resume(null)
            }
        }
    }
}
