package com.gekkotron.oikos.push

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Instant

/** What Settings and the Security banner say about push (push alerts spec §5.6, §7.4). */
sealed interface PushStatus {
    data object NotificationsOff : PushStatus

    data class Registered(val at: Instant) : PushStatus

    data object Waiting : PushStatus

    /** [reason] is the last attempt's short, secret-free message. */
    data class Failed(val reason: String) : PushStatus

    data object Demo : PushStatus
}

internal fun pushStatus(
    target: PushTarget,
    notificationsEnabled: Boolean,
    last: LastRegistration?,
    workActive: Boolean,
    lastError: String?,
): PushStatus = when {
    target == PushTarget.Demo -> PushStatus.Demo
    !notificationsEnabled -> PushStatus.NotificationsOff
    lastError != null -> PushStatus.Failed(lastError)
    workActive -> PushStatus.Waiting
    target is PushTarget.Ready && last != null && last.topic == target.tokenTopic -> PushStatus.Registered(last.at)
    else -> PushStatus.Waiting
}

/** A seam so screens and their tests need no WorkManager. */
interface PushStatusSource {
    val status: StateFlow<PushStatus>

    /** Re-reads the notification permission; call when a screen resumes. */
    fun refresh()

    /** "Register again" in Settings. */
    fun registerAgain()
}

class DefaultPushStatusSource(
    targets: PushTargets,
    store: RegistrationStore,
    queue: RegistrationQueue,
    private val registrar: PushRegistrar,
    notificationsEnabled: () -> Boolean,
    scope: CoroutineScope,
) : PushStatusSource {
    private val refreshes = MutableStateFlow(0)

    override val status: StateFlow<PushStatus> =
        combine(targets.changes, store.observe(), queue.active, registrar.lastError, refreshes) { target, last, active, error, _ ->
            pushStatus(target, notificationsEnabled(), last, active, error)
        }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, PushStatus.Waiting)

    override fun refresh() = refreshes.update { it + 1 }

    override fun registerAgain() = registrar.request()
}
