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
        assertEquals(ProfileState.Ready(direct), r.state.first { it is ProfileState.Ready })
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
        assertEquals(ProfileState.Ready(profile), r.state.first { it is ProfileState.Ready })
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
    fun `a profile without secrets needs no key`() = runTest {
        val f = file()
        val plain = ConnectionProfile.Direct(LocalBroker("broker.local"))
        repo(f).apply { save(plain); state.first { it is ProfileState.Ready } }
        val copy = file("copy.json").also { f.copyTo(it) }
        secrets.failDecrypt = true
        assertEquals(ProfileState.Ready(plain), repo(copy).settled())
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
}
