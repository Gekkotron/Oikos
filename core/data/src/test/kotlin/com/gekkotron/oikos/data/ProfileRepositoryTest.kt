package com.gekkotron.oikos.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val secrets = FakeSecretStore()
    private val gatewayKey = ByteArray(32) { it.toByte() }
    private val direct = ConnectionProfile.Direct(
        LocalBroker("192.168.1.91", 1883, username = "oikos", password = "hunter2-secret"),
        prefix = "homeassistant",
        gateway = GatewayConfig("https://gw.example", gatewayKey, "192.168.1.91"),
        pushTokenTopic = "fcm-push/tokens/{installId}",
    )

    private fun file(name: String = "profile.json") = tmp.root.resolve(name)
    private fun TestScope.repo(f: File = file()) = ProfileRepository.create(f, secrets, backgroundScope)
    private suspend fun ProfileRepository.settled() = state.first { it !is ProfileState.Loading }

    @Test
    fun `empty store is None`() = runTest {
        assertEquals(ProfileState.None, repo().settled())
    }

    @Test
    fun `direct profile round-trips`() = runTest {
        val r = repo()
        r.save(direct)
        assertEquals(ProfileState.Ready(direct, roomsPending = true, notificationsPending = true), r.state.first { it is ProfileState.Ready })
    }

    @Test
    fun `demo profile round-trips`() = runTest {
        val r = repo()
        r.save(ConnectionProfile.Demo)
        assertEquals(ProfileState.Ready(ConnectionProfile.Demo), r.state.first { it is ProfileState.Ready })
    }

    @Test
    fun `unicode password round-trips`() = runTest {
        val r = repo()
        val profile = ConnectionProfile.Direct(LocalBroker("broker.local", username = "u", password = "p@ss wörd:1 🔑"))
        r.save(profile)
        assertEquals(ProfileState.Ready(profile, roomsPending = true, notificationsPending = true), r.state.first { it is ProfileState.Ready })
    }

    @Test
    fun `secrets never appear in the file`() = runTest {
        val f = file()
        val r = repo(f)
        r.save(direct)
        r.state.first { it is ProfileState.Ready }
        val text = f.readText()
        assertTrue("192.168.1.91" in text, "sanity: the file holds the profile")
        assertFalse("hunter2-secret" in text)
        assertFalse(Base64.getEncoder().encodeToString(gatewayKey) in text)
        assertFalse(gatewayKey.joinToString("") { "%02x".format(it) } in text)
    }

    @Test
    fun `undecryptable secrets make the profile Locked`() = runTest {
        val f = file()
        val r = repo(f)
        r.save(direct)
        r.state.first { it is ProfileState.Ready }
        val copy = file("copy.json").also { f.copyTo(it) }
        secrets.failDecrypt = true
        assertEquals(ProfileState.Locked, repo(copy).settled())
    }

    @Test
    fun `an unexpected keystore exception on decrypt makes the profile Locked, not a crash`() = runTest {
        val f = file()
        val r = repo(f)
        r.save(direct)
        r.state.first { it is ProfileState.Ready }
        val copy = file("copy.json").also { f.copyTo(it) }
        secrets.decryptCrash = java.security.ProviderException("keystore daemon died")
        assertEquals(ProfileState.Locked, repo(copy).settled())
    }

    @Test
    fun `a profile without secrets needs no key`() = runTest {
        val f = file()
        val plain = ConnectionProfile.Direct(LocalBroker("broker.local"))
        repo(f).apply { save(plain); state.first { it is ProfileState.Ready } }
        val copy = file("copy.json").also { f.copyTo(it) }
        secrets.failDecrypt = true
        assertEquals(ProfileState.Ready(plain, roomsPending = true, notificationsPending = true), repo(copy).settled())
    }

    @Test
    fun `clear resets to None and keeps installId`() = runTest {
        val r = repo()
        val id = r.installId()
        r.save(direct)
        r.clear()
        assertEquals(ProfileState.None, r.state.first { it == ProfileState.None })
        assertEquals(id, r.installId())
    }

    @Test
    fun `installId is a stable UUID`() = runTest {
        val r = repo()
        val id = r.installId()
        UUID.fromString(id)
        assertEquals(id, r.installId())
    }

    @Test
    fun `a corrupt file falls back to an empty profile`() = runTest {
        val f = file().apply { writeText("{not json") }
        assertEquals(ProfileState.None, repo(f).settled())
    }

    @Test
    fun `a direct profile asks for rooms until finishRooms, and that survives a restart`() = runTest {
        val f = file()
        val r = repo(f)
        r.save(direct)
        assertEquals(ProfileState.Ready(direct, roomsPending = true, notificationsPending = true), r.state.first { it is ProfileState.Ready })
        r.finishRooms()
        r.finishNotifications()
        assertEquals(ProfileState.Ready(direct), r.state.first { it == ProfileState.Ready(direct) })
        val copy = tmp.root.resolve("copy.json")
        f.copyTo(copy)
        assertEquals(ProfileState.Ready(direct), repo(copy).settled())
    }

    @Test
    fun `saving a new direct profile asks for rooms again`() = runTest {
        val r = repo()
        r.save(direct)
        r.finishRooms()
        r.finishNotifications()
        r.state.first { it == ProfileState.Ready(direct) }
        r.clear()
        r.save(direct)
        assertEquals(ProfileState.Ready(direct, roomsPending = true, notificationsPending = true), r.state.first { it is ProfileState.Ready })
    }

    @Test
    fun `a profile file written before the flag existed does not ask for rooms`() = runTest {
        val f = file()
        repo(f).apply { save(ConnectionProfile.Direct(LocalBroker("broker.local"))); state.first { it is ProfileState.Ready } }
        val old = tmp.root.resolve("old.json")
        old.writeText(f.readText().replace(Regex(""",?"roomsPending":(true|false)"""), ""))
        assertEquals(false, (repo(old).settled() as ProfileState.Ready).roomsPending)
    }

    @Test
    fun `route defaults to AUTO and is saved`() = runTest {
        val r = repo()
        r.save(direct)
        assertEquals(RoutePreference.AUTO, r.route.value)
        r.setRoute(RoutePreference.GATEWAY)
        assertEquals(RoutePreference.GATEWAY, r.route.first { it == RoutePreference.GATEWAY })
        assertTrue("\"GATEWAY\"" in file().readText())
    }

    @Test
    fun `clear resets the route`() = runTest {
        val r = repo()
        r.save(direct)
        r.setRoute(RoutePreference.DIRECT)
        r.route.first { it == RoutePreference.DIRECT }
        r.clear()
        assertEquals(RoutePreference.AUTO, r.route.first { it == RoutePreference.AUTO })
    }

    @Test
    fun `changing the route leaves the profile state equal and keeps roomsPending`() = runTest {
        val r = repo()
        r.save(direct)
        val before = r.state.first { it is ProfileState.Ready }
        r.setRoute(RoutePreference.GATEWAY)
        r.route.first { it == RoutePreference.GATEWAY }
        assertEquals(before, r.state.value)
        assertEquals(ProfileState.Ready(direct, roomsPending = true, notificationsPending = true), r.state.value)
    }

    @Test
    fun `a saved direct profile asks for the notification step, the demo does not`() = runTest {
        val r = repo()
        r.save(direct)
        assertEquals(true, (r.state.first { it is ProfileState.Ready } as ProfileState.Ready).notificationsPending)
        r.save(ConnectionProfile.Demo)
        assertEquals(false, (r.state.first { it is ProfileState.Ready && it.profile == ConnectionProfile.Demo } as ProfileState.Ready).notificationsPending)
    }

    @Test
    fun `finishNotifications clears only its own flag`() = runTest {
        val r = repo()
        r.save(direct)
        r.finishNotifications()
        val ready = r.state.first { it is ProfileState.Ready && !it.notificationsPending } as ProfileState.Ready
        assertEquals(true, ready.roomsPending)
        r.finishRooms()
        assertEquals(ProfileState.Ready(direct, roomsPending = false, notificationsPending = false), r.state.first { it is ProfileState.Ready && !it.roomsPending })
    }

    @Test
    fun `a profile file from before this field asks for the notification step once`() = runTest {
        val f = file()
        repo(f).apply { save(direct); state.first { it is ProfileState.Ready } }
        val old = tmp.root.resolve("old-notifications.json")
        val text = f.readText()
        assertTrue("\"notificationsPending\"" in text)
        old.writeText(text.replace(Regex(""",?"notificationsPending":(true|false)"""), ""))
        assertTrue("notificationsPending" !in old.readText())
        val upgraded = repo(old)
        assertEquals(true, (upgraded.settled() as ProfileState.Ready).notificationsPending)
        assertEquals(true, (upgraded.state.value as ProfileState.Ready).roomsPending)
        upgraded.finishNotifications()
        assertEquals(true, (upgraded.state.first { it is ProfileState.Ready && !it.notificationsPending } as ProfileState.Ready).roomsPending)
        assertTrue("\"notificationsPending\":false" in old.readText())
    }

    @Test
    fun `setting the route keeps the notification step pending`() = runTest {
        val r = repo()
        r.save(direct)
        r.state.first { it is ProfileState.Ready }
        r.setRoute(RoutePreference.GATEWAY)
        r.route.first { it == RoutePreference.GATEWAY }
        assertEquals(true, (r.state.value as ProfileState.Ready).notificationsPending)
    }

    @Test
    fun `history url round-trips and can be set and cleared in place`() = runTest {
        val r = repo()
        r.save(direct.copy(historyUrl = "http://192.168.1.91:8090"))
        assertEquals("http://192.168.1.91:8090", ((r.state.first { it is ProfileState.Ready } as ProfileState.Ready).profile as ConnectionProfile.Direct).historyUrl)
        r.finishRooms()
        r.setHistoryUrl("http://10.0.0.2:8090")
        val ready = r.state.first { ((it as? ProfileState.Ready)?.profile as? ConnectionProfile.Direct)?.historyUrl == "http://10.0.0.2:8090" } as ProfileState.Ready
        assertFalse(ready.roomsPending) // an in-place edit, not a new pairing
        r.setHistoryUrl(null)
        r.state.first { ((it as? ProfileState.Ready)?.profile as? ConnectionProfile.Direct)?.historyUrl == null }
    }

    @Test
    fun `a profile file from before the history url has none`() = runTest {
        val f = file()
        f.writeText("""{"installId":"x","mode":"DIRECT","local":{"host":"h","port":1883,"tls":false,"ws":false},"prefix":"homeassistant"}""")
        assertNull(((repo(f).settled() as ProfileState.Ready).profile as ConnectionProfile.Direct).historyUrl)
    }
}
