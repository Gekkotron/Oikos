package com.gekkotron.oikos.presence

import com.gekkotron.oikos.data.PresenceConfig
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Chooses [PresenceState] from a GPS fix and a [PresenceConfig]. Pure logic (haversine), tested as JVM. */
object PresenceDecision {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun stateOf(fixLat: Double, fixLng: Double, config: PresenceConfig): PresenceState? {
        val homeLat = config.homeLat ?: return null
        val homeLng = config.homeLng ?: return null
        val distance = haversineMeters(fixLat, fixLng, homeLat, homeLng)
        return if (distance <= config.radiusM) PresenceState.HOME else PresenceState.AWAY
    }

    private fun haversineMeters(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val dLat = Math.toRadians(bLat - aLat)
        val dLng = Math.toRadians(bLng - aLng)
        val lat1 = Math.toRadians(aLat)
        val lat2 = Math.toRadians(bLat)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }
}
