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
 * Reacts to a silent FCM "presence_refresh" command: requests a fresh fix, decides HOME / AWAY from the configured home,
 * and re-publishes the three retained MQTT messages. A no-op when presence is disabled, the alias is blank, no home is
 * configured, or no fix is available (the next real geofence transition will correct the broker in that case).
 */
class PresenceRefreshWorker(
    context: Context,
    params: WorkerParameters,
    private val publisher: TopicPublisher,
    private val preferences: AppPreferences,
    private val fixSource: CurrentLocationSource,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val config = preferences.presence.filterNotNull().first()
        if (!config.enabled || config.alias.isBlank() || config.homeLat == null || config.homeLng == null) return Result.success()
        val fix = fixSource.current() ?: return Result.success()
        val state = PresenceDecision.stateOf(fix.first, fix.second, config) ?: return Result.success()
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
            if (error != null) return if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
        return Result.success()
    }

    companion object {
        const val TAG = "presence-refresh"
        const val MAX_ATTEMPTS = 5
    }
}
