package com.gekkotron.oikos

import com.gekkotron.oikos.di.WorkModule
import com.gekkotron.oikos.push.RegistrationQueue
import com.gekkotron.oikos.push.ReplyQueue
import com.gekkotron.oikos.push.ReplyState
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.util.UUID
import javax.inject.Singleton

/** Robolectric does not initialise WorkManager; the graph tests need queues that never touch it. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [WorkModule::class])
object TestWorkModule {
    /** Counts what the graph asked for. */
    class RecordingRegistrationQueue : RegistrationQueue {
        @Volatile
        var enqueued = 0
        override val active = MutableStateFlow(false)

        override fun enqueue() {
            enqueued++
        }

        override fun cancel() {}
    }

    @Provides
    @Singleton
    fun registrationQueue(): RegistrationQueue = RecordingRegistrationQueue()

    @Provides
    @Singleton
    fun replyQueue(): ReplyQueue = object : ReplyQueue {
        override fun enqueue(alertId: String, actionId: String, label: String): UUID = UUID.randomUUID()

        override fun state(id: UUID): Flow<ReplyState> = emptyFlow()

        override fun cancelAll() {}
    }
}
