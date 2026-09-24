package com.gekkotron.oikos.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreTest {
    private val alias = "oikos.test.${UUID.randomUUID()}"
    private val secret = "hunter2-secret".encodeToByteArray()

    @After
    fun tearDown() = deleteKey()

    private fun deleteKey() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)

    @Test
    fun roundTrip() {
        val store = KeystoreSecretStore(alias)
        val cipher = store.encrypt(secret)
        assertFalse("hunter2" in String(cipher, Charsets.ISO_8859_1))
        assertContentEquals(secret, store.decrypt(cipher))
    }

    @Test
    fun twoEncryptionsDiffer() {
        val store = KeystoreSecretStore(alias)
        assertFalse(store.encrypt(secret).contentEquals(store.encrypt(secret)))
    }

    @Test
    fun missingKeyIsUnavailable() {
        val cipher = KeystoreSecretStore(alias).encrypt(secret)
        deleteKey()
        assertFailsWith<SecretUnavailable> { KeystoreSecretStore(alias).decrypt(cipher) }
    }

    @Test
    fun tamperedCipherIsUnavailable() {
        val store = KeystoreSecretStore(alias)
        val cipher = store.encrypt(secret).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertFailsWith<SecretUnavailable> { store.decrypt(cipher) }
    }
}
