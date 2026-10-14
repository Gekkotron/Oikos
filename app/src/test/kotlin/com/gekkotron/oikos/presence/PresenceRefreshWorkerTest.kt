package com.gekkotron.oikos.presence

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.gekkotron.oikos.data.AppPreferences
import com.gekkotron.oikos.data.PresenceConfig
import com.gekkotron.oikos.push.TopicPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class PresenceRefreshWorkerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val publisher = Recorder()
    private val fixSource = FakeFixSource()
    private lateinit var prefs: AppPreferences

    private fun prefs(enabled: Boolean = true, alias: String = "pixel", home: Pair<Double, Double>? = 48.0 to 2.0): AppPreferences {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val p = AppPreferences.create(tmp.newFile().also { it.delete() }, scope)
        runBlocking {
            p.setPresence(PresenceConfig(enabled = enabled, alias = alias, homeLat = home?.first, homeLng = home?.second, radiusM = 150))
            p.presence.filterNotNull().first { it.enabled == enabled && it.alias == alias }
        }
        return p
    }

    private fun run(attempt: Int = 0): Result = runBlocking {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: android.content.Context, workerClassName: String, workerParameters: WorkerParameters) =
                if (workerClassName == PresenceRefreshWorker::class.java.name)
                    PresenceRefreshWorker(appContext, workerParameters, publisher, prefs, fixSource) else null
        }
        TestListenableWorkerBuilder<PresenceRefreshWorker>(context).setWorkerFactory(factory).setRunAttemptCount(attempt).build().doWork()
    }

    @Test
    fun `a disabled config publishes nothing and succeeds`() {
        prefs = prefs(enabled = false)
        fixSource.fix = 48.0 to 2.0
        assertEquals(Result.success(), run())
        assertTrue(publisher.sent.isEmpty())
    }

    @Test
    fun `a config without a home publishes nothing and succeeds`() {
        prefs = prefs(home = null)
        fixSource.fix = 48.0 to 2.0
        assertEquals(Result.success(), run())
        assertTrue(publisher.sent.isEmpty())
    }

    @Test
    fun `a blank alias publishes nothing and succeeds`() {
        prefs = prefs(alias = "")
        fixSource.fix = 48.0 to 2.0
        assertEquals(Result.success(), run())
        assertTrue(publisher.sent.isEmpty())
    }

    @Test
    fun `no fix publishes nothing and succeeds — the next real transition will correct`() {
        prefs = prefs()
        fixSource.fix = null
        assertEquals(Result.success(), run())
        assertTrue(publisher.sent.isEmpty())
    }

    @Test
    fun `a fix inside the radius publishes home`() {
        prefs = prefs()
        fixSource.fix = 48.0005 to 2.0005
        assertEquals(Result.success(), run())
        assertEquals(3, publisher.sent.size)
        assertEquals("home", publisher.sent.single { it.topic.endsWith("/state") }.payload)
    }

    @Test
    fun `a fix outside the radius publishes not_home`() {
        prefs = prefs()
        fixSource.fix = 48.01 to 2.01
        assertEquals(Result.success(), run())
        assertEquals("not_home", publisher.sent.single { it.topic.endsWith("/state") }.payload)
    }

    @Test
    fun `a failing publish retries then fails at the max`() {
        prefs = prefs()
        fixSource.fix = 48.0 to 2.0
        publisher.result = kotlin.Result.failure(java.io.IOException("offline"))
        for (attempt in 0 until PresenceRefreshWorker.MAX_ATTEMPTS - 1) {
            publisher.sent.clear()
            assertEquals(Result.retry(), run(attempt = attempt))
        }
        publisher.sent.clear()
        assertEquals(Result.failure(), run(attempt = PresenceRefreshWorker.MAX_ATTEMPTS - 1))
    }

    private class Recorder : TopicPublisher {
        class Sent(val topic: String, val payload: String, val retain: Boolean)
        val sent = mutableListOf<Sent>()
        var result: kotlin.Result<Unit> = kotlin.Result.success(Unit)
        override suspend fun publish(topic: String, payload: ByteArray, retain: Boolean): kotlin.Result<Unit> {
            sent += Sent(topic, payload.decodeToString(), retain)
            return result
        }
    }

    private class FakeFixSource : CurrentLocationSource {
        var fix: Pair<Double, Double>? = null
        override suspend fun current(): Pair<Double, Double>? = fix
    }
}
