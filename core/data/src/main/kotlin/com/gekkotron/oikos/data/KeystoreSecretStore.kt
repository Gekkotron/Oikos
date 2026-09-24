package com.gekkotron.oikos.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-256-GCM with a non-exportable Android Keystore key. Output: `iv[12] ‖ ciphertext ‖ tag[16]` (spec §4.3). */
class KeystoreSecretStore(private val alias: String = "oikos.secrets.v1") : SecretStore {
    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey()) // Keystore picks a random IV
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(cipher: ByteArray): ByteArray {
        if (cipher.size < IV_BYTES + TAG_BITS / 8) throw SecretUnavailable("ciphertext too short")
        // Never create a key here: a missing key means the data is unrecoverable (ProfileState.Locked).
        val key = existingKey() ?: throw SecretUnavailable("keystore key missing")
        return try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, cipher, 0, IV_BYTES))
                doFinal(cipher, IV_BYTES, cipher.size - IV_BYTES)
            }
        } catch (e: GeneralSecurityException) {
            throw SecretUnavailable("cannot decrypt", e)
        }
    }

    private fun existingKey(): SecretKey? =
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.getKey(alias, null) as? SecretKey

    private fun createKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
        init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
