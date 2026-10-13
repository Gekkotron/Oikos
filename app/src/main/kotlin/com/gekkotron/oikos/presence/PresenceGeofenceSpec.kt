package com.gekkotron.oikos.presence

import com.gekkotron.oikos.data.PresenceConfig

/** Pure projection of a [PresenceConfig] into the single geofence we register, or null when there is nothing to register yet. */
data class PresenceGeofenceSpec(val requestId: String, val lat: Double, val lng: Double, val radiusM: Int) {
    companion object {
        const val REQUEST_ID = "oikos.presence.home"

        fun from(config: PresenceConfig): PresenceGeofenceSpec? {
            if (!config.enabled) return null
            val lat = config.homeLat ?: return null
            val lng = config.homeLng ?: return null
            return PresenceGeofenceSpec(REQUEST_ID, lat, lng, config.radiusM)
        }
    }
}
