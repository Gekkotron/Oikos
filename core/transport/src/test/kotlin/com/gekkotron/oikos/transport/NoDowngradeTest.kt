package com.gekkotron.oikos.transport

import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoDowngradeTest {
    @Test
    fun `noDowngrade refuses https to http redirects and keeps the other settings`() {
        val base = OkHttpClient.Builder().connectTimeout(7, TimeUnit.SECONDS).build()
        assertTrue(base.followSslRedirects)
        val safe = base.noDowngrade()
        assertFalse(safe.followSslRedirects)
        assertEquals(7_000, safe.connectTimeoutMillis)
    }

    @Test
    fun `both gateway classes build their client with noDowngrade`() {
        val src = { n: String -> java.io.File("src/main/kotlin/com/gekkotron/oikos/transport/$n").readText() }
        assertTrue("noDowngrade()" in src("AthenaGatewayApi.kt"))
        assertTrue("noDowngrade()" in src("GatewayDownloader.kt"))
    }
}
