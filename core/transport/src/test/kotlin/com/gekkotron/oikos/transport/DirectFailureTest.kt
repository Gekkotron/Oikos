package com.gekkotron.oikos.transport

import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.assertEquals

class DirectFailureTest {
    @Test
    fun `refused and unknown hosts are unreachable`() {
        assertEquals(DirectFailure.Unreachable, classifyFailure(ConnectException("Connection refused")))
        assertEquals(DirectFailure.Unreachable, classifyFailure(RuntimeException("wrapped", UnknownHostException("nas.lan"))))
    }

    @Test
    fun `TLS problems anywhere in the chain are TLS`() {
        val failure = classifyFailure(RuntimeException("connect failed", SSLHandshakeException("PKIX path building failed")))
        assertEquals(DirectFailure.Tls("PKIX path building failed"), failure)
    }

    @Test
    fun `anything else keeps its message`() {
        assertEquals(DirectFailure.Other("boom"), classifyFailure(IllegalStateException("boom")))
    }
}
