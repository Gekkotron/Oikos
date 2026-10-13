package com.gekkotron.oikos.push

/**
 * Triggered by a silent FCM command (`cmd=presence_refresh`): schedules a one-shot that fetches a fresh GPS fix and
 * re-publishes the HA MQTT Discovery device_tracker state. Implemented in `:app` (WorkManager); kept as an interface
 * here so `:core:push` stays independent of `:app`.
 */
fun interface PresenceRefresher {
    fun requestRefresh()
}
