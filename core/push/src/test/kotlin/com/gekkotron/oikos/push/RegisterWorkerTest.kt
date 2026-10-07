package com.gekkotron.oikos.push

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.time.Instant
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class RegisterWorkerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val targets = FakeTargets()
    private val tokens = FakeFcmTokens()
    private val publisher = RecordingPublisher()

    private fun run(pub: TopicPublisher = publisher): Result = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val store = RegistrationStore.create(tmp.newFile().also { it.delete() }, scope)
        val registrar = PushRegistrar(targets, tokens, pub, store, FakeQueue(), { PushRegistrar.DeviceInfo("Pixel", "en") }, { Instant.EPOCH })
        TestListenableWorkerBuilder<RegisterWorker>(context).setWorkerFactory(PushWorkerFactory(registrar)).build().doWork()
    }

    @Test
    fun `a published registration is a success`() {
        assertEquals(Result.success(), run())
    }

    @Test
    fun `a publish failure is retried with the worker backoff`() {
        publisher.result = kotlin.Result.failure(IOException("offline"))
        assertEquals(Result.retry(), run())
    }

    @Test
    fun `a publisher that throws is retried, not failed for good`() {
        val throwing = TopicPublisher { _, _, _ -> throw IllegalStateException("boom") }
        assertEquals(Result.retry(), run(throwing))
    }

    @Test
    fun `no profile finishes without a retry`() {
        targets.target = PushTarget.None
        assertEquals(Result.success(), run())
    }

    @Test
    fun `a build without Firebase fails for good`() {
        tokens.failure = FirebaseUnavailable()
        assertEquals(Result.failure(), run())
    }

    @Test
    fun `the unique name is register`() {
        assertEquals("register", RegisterWorker.UNIQUE_NAME)
    }
}
