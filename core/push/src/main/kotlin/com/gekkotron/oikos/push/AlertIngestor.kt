package com.gekkotron.oikos.push

import android.util.Log
import com.gekkotron.oikos.alerts.AlertParser
import com.gekkotron.oikos.alerts.AlertRepository

/** What `onMessageReceived` does with an FCM data map (push alerts spec §5.2), apart from the Android plumbing. */
class AlertIngestor(
    private val repository: AlertRepository,
    private val notifier: AlertNotifier,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) {
    sealed interface Outcome {
        data object Dropped : Outcome

        data class Cancelled(val id: String) : Outcome

        data class Shown(val id: String) : Outcome

        /** Stored for the history but not posted: notifications are off, or a newer message superseded this one. */
        data class StoredOnly(val id: String) : Outcome
    }

    suspend fun onMessage(data: Map<String, String>): Outcome {
        val alert = AlertParser.fromData(data) ?: run {
            // Key names only: titles and bodies are the user's private data.
            log("dropped a malformed alert (keys: ${data.keys.sorted().joinToString()})")
            return Outcome.Dropped
        }
        if (alert.resolved) {
            notifier.cancel(alert.id)
            repository.markResolved(alert.id)
            return Outcome.Cancelled(alert.id)
        }
        repository.upsert(alert)
        return if (notifier.post(alert)) Outcome.Shown(alert.id) else Outcome.StoredOnly(alert.id)
    }

    private companion object {
        const val TAG = "OikosPush"
    }
}
