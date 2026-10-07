package com.gekkotron.oikos.push

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** WorkManager behind a seam: unique work `register`, REPLACE (push alerts spec §5.4). */
interface RegistrationQueue {
    fun enqueue()

    fun cancel()

    /** True while registration work is enqueued, running or waiting out a backoff. */
    val active: Flow<Boolean>
}

suspend fun RegistrationQueue.isActive(): Boolean = active.first()

/** Spec §5.4: when and how this install tells OikosPush its FCM token. */
class PushRegistrar(
    private val targets: PushTargets,
    private val tokens: FcmTokens,
    private val publisher: TopicPublisher,
    private val store: RegistrationStore,
    private val queue: RegistrationQueue,
    private val device: () -> DeviceInfo,
    private val clock: () -> Instant = Instant::now,
    private val unregisterBudget: Duration = 5.seconds,
) {
    data class DeviceInfo(val name: String, val lang: String)

    sealed interface RegisterOutcome {
        data object Done : RegisterOutcome

        /** No profile, the demo, or no route at all: finish without retrying. */
        data object NotConfigured : RegisterOutcome

        /** Terminal: retrying cannot help (a build without Firebase). */
        data class Unavailable(val reason: String) : RegisterOutcome

        data class Retry(val reason: String) : RegisterOutcome
    }

    private val _lastError = MutableStateFlow<String?>(null)

    /** The last attempt's short, secret-free message; null after a success. Read by `PushStatus`. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** The worker's body. */
    suspend fun register(): RegisterOutcome {
        val target = targets.current() as? PushTarget.Ready ?: return RegisterOutcome.NotConfigured
        val token = try {
            tokens.token()
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseUnavailable) {
            return failed(RegisterOutcome.Unavailable(e.message.orEmpty()))
        } catch (e: Exception) {
            return failed(RegisterOutcome.Retry(reasonOf(e)))
        }
        val info = device()
        val payload = buildJsonObject {
            put("token", token)
            put("device_name", info.name)
            put("lang", info.lang)
            put("updated_at", clock().epochSecond)
        }
        // A publisher may throw instead of returning a failure; anything but cancellation is retryable.
        val result = try {
            publisher.publish(target.tokenTopic, payload.toString().encodeToByteArray(), retain = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        val error = result.exceptionOrNull()
        return when {
            error == null -> {
                store.save(token, target.tokenTopic, clock())
                _lastError.value = null
                RegisterOutcome.Done
            }
            error is NotConfigured -> RegisterOutcome.NotConfigured
            else -> failed(RegisterOutcome.Retry(reasonOf(error)))
        }
    }

    /** A new FCM token, the end of onboarding or "Register again": replaces any pending work. */
    fun request() = queue.enqueue()

    /**
     * Every time the live transport connects. Re-enqueues only when the token or the topic differs from the last
     * successful registration, and never over work that is already pending: REPLACE would restart its backoff on
     * every flap of the link.
     */
    suspend fun onLinkConnected() {
        val target = targets.current() as? PushTarget.Ready ?: return
        val token = try {
            tokens.token()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return // register() reports it when it runs; a connect has nothing to add
        }
        val last = store.last()
        if (last != null && last.token == token && last.topic == target.tokenTopic) return
        if (!queue.isActive()) queue.enqueue()
    }

    /**
     * "Set up again", before the profile is wiped. Order matters: pending registration work is cancelled first so
     * it cannot publish the token again after the empty payload; the publish is best effort and capped.
     */
    suspend fun unregister() {
        queue.cancel()
        (targets.current() as? PushTarget.Ready)?.let { target ->
            try {
                withTimeoutOrNull(unregisterBudget) { publisher.publish(target.tokenTopic, ByteArray(0), retain = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best effort: the rest of the cleanup must still run.
            }
        }
        try {
            tokens.delete()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort: the token is useless to OikosPush once its retained entry is gone.
        }
        store.clear()
        _lastError.value = null
    }

    private fun failed(outcome: RegisterOutcome): RegisterOutcome {
        _lastError.value = when (outcome) {
            is RegisterOutcome.Retry -> outcome.reason
            is RegisterOutcome.Unavailable -> outcome.reason
            else -> null
        }
        return outcome
    }

    private fun reasonOf(e: Throwable): String = (e.message ?: e.javaClass.simpleName).take(MAX_REASON)

    private companion object {
        const val MAX_REASON = 80
    }
}
