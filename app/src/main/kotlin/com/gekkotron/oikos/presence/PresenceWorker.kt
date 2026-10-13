package com.gekkotron.oikos.presence

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gekkotron.oikos.data.AppPreferences
import com.gekkotron.oikos.data.PresenceConfig
import com.gekkotron.oikos.push.TopicPublisher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * Publishes HA MQTT Discovery for the device_tracker and the new presence state. Retained so a late HA pick-up still sees
 * the right value. Three publishes: discovery config, availability online, state (home / not_home).
 */
class PresenceWorker(
    context: Context,
    params: WorkerParameters,
    private val publisher: TopicPublisher,
    private val preferences: AppPreferences,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val stateName = inputData.getString(KEY_STATE) ?: return Result.failure()
        val state = runCatching { PresenceState.valueOf(stateName) }.getOrNull() ?: return Result.failure()
        val config = preferences.presence.filterNotNull().first()
        if (!config.enabled || config.alias.isBlank()) return Result.success()
        return publishAll(config, state)
    }

    private suspend fun publishAll(config: PresenceConfig, state: PresenceState): Result {
        val alias = config.alias
        val publishes = listOf(
            PresenceTopics.discovery(alias) to PresencePayloads.discovery(alias),
            PresenceTopics.availability(alias) to PresencePayloads.AVAILABLE_ONLINE,
            PresenceTopics.state(alias) to PresencePayloads.state(state),
        )
        for ((topic, payload) in publishes) {
            val error = try {
                publisher.publish(topic, payload.encodeToByteArray(), retain = true).exceptionOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
            if (error != null) return retryOrFail()
        }
        return Result.success()
    }

    private fun retryOrFail(): Result =
        if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()

    companion object {
        const val TAG = "presence"
        const val KEY_STATE = "state"
        const val MAX_ATTEMPTS = 5
    }
}
