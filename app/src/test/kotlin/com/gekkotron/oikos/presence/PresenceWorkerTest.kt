package com.gekkotron.oikos.presence

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.gekkotron.oikos.data.AppPreferences
import com.gekkotron.oikos.data.PresenceConfig
import com.gekkotron.oikos.push.TopicPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class PresenceWorkerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val publisher = Recorder()
    private lateinit var prefs: AppPreferences

    private fun prefs(enabled: Boolean = true, alias: String = "pixel"): AppPreferences {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val p = AppPreferences.create(tmp.newFile().also { it.delete() }, scope)
        runBlocking {
            p.setPresence(PresenceConfig(enabled = enabled, alias = alias, homeLat = 48.0, homeLng = 2.0, radiusM = 150))
            p.presence.filterNotNull().first { it.enabled == enabled && it.alias == alias }
        }
        return p
    }

    private fun run(state: PresenceState, attempt: Int = 0, pub: TopicPublisher = publisher, p: AppPreferences = prefs): Result = runBlocking {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: android.content.Context, workerClassName: String, workerParameters: WorkerParameters) =
                if (workerClassName == PresenceWorker::class.java.name) PresenceWorker(appContext, workerParameters, pub, p) else null
        }
        val input = workDataOf(PresenceWorker.KEY_STATE to state.name)
        TestListenableWorkerBuilder<PresenceWorker>(context).setWorkerFactory(factory).setInputData(input).setRunAttemptCount(attempt).build().doWork()
    }

    @Test
    fun `it publishes discovery, availability online, and state as retained for an enabled config`() {
        prefs = prefs()
        assertEquals(Result.success(), run(PresenceState.AWAY))
        val sent = publisher.sent
        assertEquals(3, sent.size, "expected discovery + availability + state, got ${sent.map { it.topic }}")
        val topics = sent.map { it.topic }
        assertTrue(topics.contains("homeassistant/device_tracker/oikos_pixel/config"), topics.toString())
        assertTrue(topics.contains("oikos/presence/pixel/available"), topics.toString())
        assertTrue(topics.contains("oikos/presence/pixel/state"), topics.toString())
        sent.forEach { assertTrue(it.retain, "${it.topic} must be retained") }
    }

    @Test
    fun `the state payload is home when ENTER and not_home when EXIT`() {
        prefs = prefs()
        assertEquals(Result.success(), run(PresenceState.HOME))
        assertEquals("home", publisher.sent.single { it.topic.endsWith("/state") }.payload)
        publisher.sent.clear()
        assertEquals(Result.success(), run(PresenceState.AWAY))
        assertEquals("not_home", publisher.sent.single { it.topic.endsWith("/state") }.payload)
    }

    @Test
    fun `availability is online so HA sees the tracker as available`() {
        prefs = prefs()
        run(PresenceState.HOME)
        assertEquals("online", publisher.sent.single { it.topic.endsWith("/available") }.payload)
    }

    @Test
    fun `a disabled config publishes nothing and succeeds`() {
        prefs = prefs(enabled = false)
        assertEquals(Result.success(), run(PresenceState.AWAY))
        assertTrue(publisher.sent.isEmpty())
    }

    @Test
    fun `a blank alias publishes nothing and succeeds`() {
        prefs = prefs(alias = "")
        assertEquals(Result.success(), run(PresenceState.AWAY))
        assertTrue(publisher.sent.isEmpty())
    }

    @Test
    fun `a failing publish retries for the first attempts and gives up at the max`() {
        prefs = prefs()
        publisher.result = kotlin.Result.failure(IOException("offline"))
        for (attempt in 0 until PresenceWorker.MAX_ATTEMPTS - 1) {
            publisher.sent.clear()
            assertEquals(Result.retry(), run(PresenceState.AWAY, attempt = attempt), "attempt ${attempt + 1}")
        }
        publisher.sent.clear()
        assertEquals(Result.failure(), run(PresenceState.AWAY, attempt = PresenceWorker.MAX_ATTEMPTS - 1))
    }

    @Test
    fun `the discovery payload carries the alias`() {
        prefs = prefs(alias = "Pixel 9")
        run(PresenceState.HOME)
        val cfg = publisher.sent.single { it.topic.contains("/config") }.payload
        val json = Json.parseToJsonElement(cfg).jsonObject
        assertEquals("Oikos Pixel 9", json["name"]!!.jsonPrimitive.content)
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
}
