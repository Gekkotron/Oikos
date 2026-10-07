package com.gekkotron.oikos.push

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class PushStatusTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val at = Instant.ofEpochSecond(1_790_000_000)
    private val ready = PushTarget.Ready("install-1", "oikos-push/tokens/install-1")
    private val registered = LastRegistration("t", "oikos-push/tokens/install-1", at)

    private fun status(
        target: PushTarget = ready,
        enabled: Boolean = true,
        last: LastRegistration? = registered,
        work: Boolean = false,
        error: String? = null,
    ) = pushStatus(target, enabled, last, work, error)

    @Test
    fun `a registration on the current topic is Registered`(): Unit {
        assertEquals(PushStatus.Registered(at), status())
    }

    @Test
    fun `the demo is Demo whatever else is true`(): Unit {
        assertEquals(PushStatus.Demo, status(target = PushTarget.Demo, enabled = false, error = "x"))
    }

    @Test
    fun `notifications off wins over a registration, a failure and pending work`(): Unit {
        assertEquals(PushStatus.NotificationsOff, status(enabled = false))
        assertEquals(PushStatus.NotificationsOff, status(enabled = false, error = "x", work = true))
    }

    @Test
    fun `an error is Failed even while a retry is waiting`(): Unit {
        assertEquals(PushStatus.Failed("broker did not answer in time"), status(error = "broker did not answer in time", work = true, last = null))
    }

    @Test
    fun `pending work is Waiting even over an older registration`(): Unit {
        assertEquals(PushStatus.Waiting, status(work = true))
    }

    @Test
    fun `no registration yet, or one for another topic, is Waiting`(): Unit {
        assertEquals(PushStatus.Waiting, status(last = null))
        assertEquals(PushStatus.Waiting, status(last = registered.copy(topic = "old/tokens/install-1")))
    }

    @Test
    fun `no profile is Waiting`(): Unit {
        assertEquals(PushStatus.Waiting, status(target = PushTarget.None, last = null))
    }

    /** The DataStore commits on Dispatchers.IO in real time, so the wait must leave the test dispatcher and use real time. */
    private suspend fun awaitStatus(source: PushStatusSource, accept: (PushStatus) -> Boolean): PushStatus =
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { source.status.first(accept) } }

    @Test
    fun `the source follows the registration, the queue, the error and a refreshed permission`(): Unit = runTest {
        val store = RegistrationStore.create(tmp.newFile().also { it.delete() }, backgroundScope)
        val queue = FakeQueue()
        val publisher = RecordingPublisher()
        val registrar = PushRegistrar(FakeTargets(), FakeFcmTokens(), publisher, store, queue, { PushRegistrar.DeviceInfo("p", "en") }, { at })
        var enabled = true
        val source = DefaultPushStatusSource(FakeTargets(), store, queue, registrar, { enabled }, backgroundScope)

        registrar.register()
        assertEquals(PushStatus.Registered(at), awaitStatus(source) { it is PushStatus.Registered })

        publisher.result = Result.failure(IOException("offline"))
        registrar.register()
        assertEquals(PushStatus.Failed("offline"), awaitStatus(source) { it is PushStatus.Failed })

        enabled = false
        source.refresh()
        assertEquals(PushStatus.NotificationsOff, awaitStatus(source) { it == PushStatus.NotificationsOff })
    }

    @Test
    fun `register again asks the registrar to enqueue`(): Unit = runTest {
        val store = RegistrationStore.create(tmp.newFile().also { it.delete() }, backgroundScope)
        val queue = FakeQueue()
        val registrar = PushRegistrar(FakeTargets(), FakeFcmTokens(), RecordingPublisher(), store, queue, { PushRegistrar.DeviceInfo("p", "en") })
        DefaultPushStatusSource(FakeTargets(), store, queue, registrar, { true }, backgroundScope).registerAgain()
        assertEquals(1, queue.enqueued)
    }
}
