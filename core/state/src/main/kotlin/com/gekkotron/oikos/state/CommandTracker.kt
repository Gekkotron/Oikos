package com.gekkotron.oikos.state

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandFailure
import com.gekkotron.oikos.model.StateValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Optimistic commands (spec §4.4): pending → confirmed by any state message, or reverted after [timeout]. */
class CommandTracker(
    private val store: StateStore,
    private val scope: CoroutineScope,
    private val timeout: Duration = 5.seconds,
) {
    private val _failures = MutableSharedFlow<CommandFailure>(extraBufferCapacity = 64)
    val failures: SharedFlow<CommandFailure> = _failures.asSharedFlow()

    private val timers = ConcurrentHashMap<String, Job>()

    /** Call before publishing. Without feedback, the caller confirms with [StateStore.confirmPending]. */
    fun track(uniqueId: String, command: Command, predicted: StateValue?, hasFeedback: Boolean) {
        timers.remove(uniqueId)?.cancel()
        if (predicted == null) return
        store.markPending(uniqueId, predicted)
        if (!hasFeedback) return
        timers[uniqueId] = scope.launch {
            delay(timeout)
            timers.remove(uniqueId, coroutineContext.job)
            if (store.revert(uniqueId)) _failures.emit(CommandFailure(uniqueId, command, "Not confirmed"))
        }
    }

    /** The publish failed: drop the prediction without reporting a timeout. */
    fun abort(uniqueId: String) {
        timers.remove(uniqueId)?.cancel()
        store.revert(uniqueId)
    }
}
