package com.gekkotron.oikos.feature.onboarding

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.SecretStore
import com.gekkotron.oikos.data.SecretUnavailable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

class FakeConnectionTester : ConnectionTester {
    val drafts = mutableListOf<ConnectionProfile.Direct>()
    val result = MutableStateFlow<TestState>(TestState.Connecting)
    var active = 0
        private set

    override fun test(draft: ConnectionProfile.Direct): Flow<TestState> = flow {
        drafts += draft
        active++
        try {
            emitAll(result)
        } finally {
            active--
        }
    }
}

/** Test-only store; flip [fail] to simulate a lost key, [failEncrypt] to simulate a Keystore that can't encrypt. */
class PassThroughSecretStore : SecretStore {
    var fail = false
    var failEncrypt = false
    override fun encrypt(plain: ByteArray) =
        if (failEncrypt) throw java.security.ProviderException("keystore unavailable") else byteArrayOf(1) + plain
    override fun decrypt(cipher: ByteArray): ByteArray = if (fail) throw SecretUnavailable("lost") else cipher.copyOfRange(1, cipher.size)
}
