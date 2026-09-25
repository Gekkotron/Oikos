package com.gekkotron.oikos.source.discovery

/**
 * zigbee2mqtt users often name devices as paths ("rooms/julien/lampe", "rdc/salon/plafond"), and HA
 * discovery carries that name unchanged. With three or more non-empty segments, the last is the device
 * and the second-to-last its room. Shorter names ("AC/DC plug") are ordinary names.
 */
internal object DevicePath {
    data class Parts(val name: String, val area: String?)

    fun split(name: String): Parts {
        val segments = name.split('/').map { it.trim() }
        if (segments.size < 3 || segments.any { it.isEmpty() }) return Parts(name, null)
        return Parts(segments.last(), segments[segments.size - 2])
    }
}
