package com.gekkotron.oikos.data

class SecretUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Encrypts the few secrets we persist (broker password, gateway key). Implementations never log inputs. */
interface SecretStore {
    fun encrypt(plain: ByteArray): ByteArray

    /** @throws SecretUnavailable when the key is gone or [cipher] does not authenticate. */
    fun decrypt(cipher: ByteArray): ByteArray
}
