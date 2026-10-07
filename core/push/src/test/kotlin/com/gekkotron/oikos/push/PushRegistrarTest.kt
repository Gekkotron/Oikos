package com.gekkotron.oikos.push

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushRegistrarTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val now = Instant.ofEpochSecond(1_790_000_000)
    private val targets = FakeTargets()
    private val tokens = FakeFcmTokens()
    private val publisher = RecordingPublisher()
    private val events = mutableListOf<String>()
    private val queue = FakeQueue(events)

    private fun TestScope.registrar(
        store: RegistrationStore = RegistrationStore.create(tmp.newFile().also { it.delete() }, backgroundScope),
        pub: TopicPublisher = publisher,
    ) = PushRegistrar(targets, tokens, pub, store, queue, device = { PushRegistrar.DeviceInfo("Pixel 6a", "fr") }, clock = { now })

    private fun TestScope.newStore() = RegistrationStore.create(tmp.newFile().also { it.delete() }, backgroundScope)

    @Test
    fun `register publishes the retained token payload to the substituted topic and remembers it`(): Unit = runTest {
        val store = newStore()
        assertEquals(PushRegistrar.RegisterOutcome.Done, registrar(store).register())
        val sent = publisher.sent.single()
        assertEquals("oikos-push/tokens/install-1", sent.topic)
        assertTrue(sent.retain)
        val json = Json.parseToJsonElement(sent.payload).jsonObject
        assertEquals("token-1", json["token"]!!.jsonPrimitive.content)
        assertEquals("Pixel 6a", json["device_name"]!!.jsonPrimitive.content)
        assertEquals("fr", json["lang"]!!.jsonPrimitive.content)
        assertEquals(1_790_000_000L, json["updated_at"]!!.jsonPrimitive.content.toLong())
        assertEquals(LastRegistration("token-1", "oikos-push/tokens/install-1", now), store.last())
    }

    @Test
    fun `a failed publish asks for a retry, keeps no registration and exposes a short reason`(): Unit = runTest {
        val store = newStore()
        publisher.result = Result.failure(IOException("broker did not answer in time"))
        val r = registrar(store)
        assertEquals(PushRegistrar.RegisterOutcome.Retry("broker did not answer in time"), r.register())
        assertNull(store.last())
        assertEquals("broker did not answer in time", r.lastError.value)
        publisher.result = Result.success(Unit)
        r.register()
        assertNull(r.lastError.value)
    }

    @Test
    fun `no profile or the demo finishes without retrying and publishes nothing`(): Unit = runTest {
        for (target in listOf(PushTarget.None, PushTarget.Demo)) {
            targets.target = target
            assertEquals(PushRegistrar.RegisterOutcome.NotConfigured, registrar().register())
        }
        assertTrue(publisher.sent.isEmpty())
    }

    @Test
    fun `a NotConfigured publish result also finishes without retrying`(): Unit = runTest {
        publisher.result = Result.failure(NotConfigured())
        assertEquals(PushRegistrar.RegisterOutcome.NotConfigured, registrar().register())
    }

    @Test
    fun `a build without Firebase is a terminal failure with a clear reason`(): Unit = runTest {
        tokens.failure = FirebaseUnavailable()
        val r = registrar()
        assertIs<PushRegistrar.RegisterOutcome.Unavailable>(r.register())
        assertEquals("Firebase is not configured in this build", r.lastError.value)
    }

    @Test
    fun `a publisher that throws is a retry, not an escape`(): Unit = runTest {
        val throwing = TopicPublisher { _, _, _ -> throw IllegalStateException("boom") }
        val r = registrar(pub = throwing)
        assertEquals(PushRegistrar.RegisterOutcome.Retry("boom"), r.register())
        assertEquals("boom", r.lastError.value)
    }

    @Test
    fun `a token error from Firebase is retried`(): Unit = runTest {
        tokens.failure = IOException("SERVICE_NOT_AVAILABLE")
        assertEquals(PushRegistrar.RegisterOutcome.Retry("SERVICE_NOT_AVAILABLE"), registrar().register())
    }

    @Test
    fun `the topic template comes from the profile`(): Unit = runTest {
        targets.target = PushTarget.Ready("install-2", "custom/tokens/install-2")
        registrar().register()
        assertEquals("custom/tokens/install-2", publisher.sent.single().topic)
    }

    @Test
    fun `a connect republishes only when the token or the topic differs from the last registration`(): Unit = runTest {
        val store = newStore()
        store.save("token-1", "oikos-push/tokens/install-1", now)
        val r = registrar(store)
        r.onLinkConnected()
        assertEquals(0, queue.enqueued)
        tokens.token = "token-2"
        r.onLinkConnected()
        assertEquals(1, queue.enqueued)
        queue.activeState.value = false
        tokens.token = "token-1"
        targets.target = PushTarget.Ready("install-1", "other/tokens/install-1")
        r.onLinkConnected()
        assertEquals(2, queue.enqueued)
    }

    @Test
    fun `a first connect with no registration yet enqueues one`(): Unit = runTest {
        registrar().onLinkConnected()
        assertEquals(1, queue.enqueued)
    }

    @Test
    fun `a connect with no profile, the demo or no Firebase enqueues nothing`(): Unit = runTest {
        val r = registrar()
        targets.target = PushTarget.None
        r.onLinkConnected()
        targets.target = PushTarget.Demo
        r.onLinkConnected()
        targets.target = PushTarget.Ready("install-1", "oikos-push/tokens/install-1")
        tokens.failure = FirebaseUnavailable()
        r.onLinkConnected()
        assertEquals(0, queue.enqueued)
    }

    @Test
    fun `a reconnect while registration is still retrying does not reset its backoff`(): Unit = runTest {
        val r = registrar()
        r.request()
        assertEquals(1, queue.enqueued)
        // The link flaps while the worker is waiting out its backoff: each connect sees the same difference.
        repeat(5) { r.onLinkConnected() }
        assertEquals(1, queue.enqueued)
        // A new FCM token or "Register again" is explicit and does replace the pending work.
        r.request()
        assertEquals(2, queue.enqueued)
    }

    @Test
    fun `unregister cancels pending work before publishing, then deletes the token and forgets the registration`(): Unit = runTest {
        val store = newStore()
        store.save("token-1", "oikos-push/tokens/install-1", now)
        val pub = RecordingPublisher(onPublish = { events += "publish" })
        registrar(store, pub).unregister()
        assertEquals(listOf("cancel", "publish"), events)
        val sent = pub.sent.single()
        assertEquals("oikos-push/tokens/install-1", sent.topic)
        assertEquals("", sent.payload)
        assertTrue(sent.retain)
        assertEquals(1, tokens.deleted)
        assertNull(store.last())
    }

    @Test
    fun `unregister gives up on a publish that hangs after 5 seconds but still finishes the cleanup`(): Unit = runTest {
        val store = newStore()
        store.save("token-1", "oikos-push/tokens/install-1", now)
        val hanging = RecordingPublisher(onPublish = { delay(60_000) })
        registrar(store, hanging).unregister()
        assertEquals(5_000, testScheduler.currentTime)
        assertEquals(1, tokens.deleted)
        assertNull(store.last())
    }

    @Test
    fun `unregister with no profile still cancels, deletes and clears`(): Unit = runTest {
        targets.target = PushTarget.None
        registrar().unregister()
        assertTrue(publisher.sent.isEmpty())
        assertEquals(1, tokens.deleted)
        assertEquals(listOf("cancel"), events)
    }
}
