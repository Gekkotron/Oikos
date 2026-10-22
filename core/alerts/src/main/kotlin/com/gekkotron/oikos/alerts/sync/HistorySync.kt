package com.gekkotron.oikos.alerts.sync

import android.util.Log
import com.gekkotron.oikos.alerts.AlertParser
import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.PushTopics
import com.gekkotron.oikos.transport.MqttMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Catches the history up from the retained `oikos/push/history` message; it never posts a notification (spec §6.2). */
class HistorySync(
    private val messages: Flow<MqttMessage>,
    private val repository: AlertRepository,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) {
    private val _catchUp = MutableStateFlow(0)

    /** Alerts that arrived through history and have not been announced yet ("*n* alerts arrived while you were offline"). */
    val catchUp: StateFlow<Int> = _catchUp.asStateFlow()

    fun acknowledge() {
        _catchUp.value = 0
    }

    /** Subscribes before returning, so a retained message that arrives at once is not missed. */
    fun start(scope: CoroutineScope): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        messages.filter { it.topic == PushTopics.HISTORY }.collect { message ->
            try {
                handle(message.payloadString(), message.retained)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A Room failure on one message must not end the collector; only the class is logged.
                log("history sync failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Applies one history message. Missing alerts are always stored, but only a [retained] delivery (the broker
     * replaying history after a (re)subscribe) counts as "arrived while you were offline"; a live republish
     * does not. Returns how many alerts were counted.
     */
    suspend fun handle(payload: String, retained: Boolean = true): Int {
        val alerts = AlertParser.parseHistory(payload) ?: run {
            log("the history message is not a JSON array")
            return 0
        }
        var added = 0
        for (alert in alerts) {
            // No title: a cancel OikosPush stored on its own. It resolves what we have; it is never a row.
            if (alert.title.isNotBlank() && repository.insertIfMissing(alert)) added++
            if (alert.resolved) repository.markResolved(alert.id)
        }
        if (!retained) return 0
        if (added > 0) _catchUp.update { it + added }
        return added
    }

    private companion object {
        const val TAG = "OikosHistory"
    }
}
