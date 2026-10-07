package com.gekkotron.oikos.push

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

class FakeTargets(var target: PushTarget = PushTarget.Ready("install-1", "oikos-push/tokens/install-1")) : PushTargets {
    override suspend fun current(): PushTarget = target
    override val changes: Flow<PushTarget> get() = flowOf(target)
}

class FakeFcmTokens(var token: String = "token-1", var failure: Exception? = null) : FcmTokens {
    var deleted = 0
    override suspend fun token(): String = failure?.let { throw it } ?: token
    override suspend fun delete() {
        deleted++
    }
}

class RecordingPublisher(var result: Result<Unit> = Result.success(Unit), val onPublish: suspend () -> Unit = {}) : TopicPublisher {
    class Sent(val topic: String, val payload: String, val retain: Boolean)

    val sent = mutableListOf<Sent>()
    override suspend fun publish(topic: String, payload: ByteArray, retain: Boolean): Result<Unit> {
        sent += Sent(topic, payload.decodeToString(), retain)
        onPublish()
        return result
    }
}

class FakeQueue(val events: MutableList<String> = mutableListOf()) : RegistrationQueue {
    val activeState = MutableStateFlow(false)
    var enqueued = 0
    override fun enqueue() {
        enqueued++
        events += "enqueue"
        activeState.value = true
    }

    override fun cancel() {
        events += "cancel"
        activeState.value = false
    }

    override val active: Flow<Boolean> get() = activeState
}
