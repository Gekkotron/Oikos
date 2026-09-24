package com.gekkotron.oikos.data

/**
 * Reversible and visibly not plaintext. Flip [failDecrypt] to simulate a lost Keystore key; set
 * [decryptCrash] to simulate a Keystore that throws something unexpected (a RuntimeException such as
 * ProviderException).
 */
class FakeSecretStore : SecretStore {
    var failDecrypt = false
    var decryptCrash: RuntimeException? = null

    override fun encrypt(plain: ByteArray): ByteArray = byteArrayOf(MARK) + plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

    override fun decrypt(cipher: ByteArray): ByteArray {
        decryptCrash?.let { throw it }
        if (failDecrypt || cipher.firstOrNull() != MARK) throw SecretUnavailable("fake key unavailable")
        return cipher.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    private companion object {
        const val MARK: Byte = 0x7F
    }
}
