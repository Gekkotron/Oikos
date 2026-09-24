package com.gekkotron.oikos.data

/** Reversible and visibly not plaintext. Flip [failDecrypt] to simulate a lost Keystore key. */
class FakeSecretStore : SecretStore {
    var failDecrypt = false

    override fun encrypt(plain: ByteArray): ByteArray = byteArrayOf(MARK) + plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

    override fun decrypt(cipher: ByteArray): ByteArray {
        if (failDecrypt || cipher.firstOrNull() != MARK) throw SecretUnavailable("fake key unavailable")
        return cipher.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    private companion object {
        const val MARK: Byte = 0x7F
    }
}
