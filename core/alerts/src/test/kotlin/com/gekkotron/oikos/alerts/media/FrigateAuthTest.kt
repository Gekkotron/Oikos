package com.gekkotron.oikos.alerts.media

import com.gekkotron.oikos.data.FrigateConfig
import com.gekkotron.oikos.transport.HttpPostResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FrigateAuthTest {
    private val login = FrigateConfig("http://192.168.1.91:8971", "admin", "pw")
    private val snap = "http://192.168.1.91:8971/api/events/1/snapshot.jpg"
    private val posts = mutableListOf<Pair<String, String>>()

    private fun auth(
        config: FrigateConfig? = login,
        answer: () -> HttpPostResult = { HttpPostResult.Response(200, mapOf("set-cookie" to "frigate_token=t${posts.size}; Path=/; HttpOnly")) },
    ) = FrigateAuth(config = { config }, post = { url, json -> posts += url to json; delay(10); answer() })

    @Test
    fun `logs in once and sends the cookie`(): Unit = runBlocking {
        val a = auth()
        assertEquals(AuthHeaders.Ready(mapOf("Cookie" to "frigate_token=t1")), a.headersFor(snap))
        assertEquals(AuthHeaders.Ready(mapOf("Cookie" to "frigate_token=t1")), a.headersFor(snap))
        assertFalse("t1" in a.headersFor(snap).toString())
        assertEquals(listOf("http://192.168.1.91:8971/api/login" to """{"user":"admin","password":"pw"}"""), posts)
    }

    @Test
    fun `concurrent fetches share one login`(): Unit = runBlocking {
        val a = auth()
        List(5) { async { a.headersFor(snap) } }.awaitAll()
        assertEquals(1, posts.size)
    }

    @Test
    fun `other hosts, ports and schemes get nothing, and default ports match`(): Unit = runBlocking {
        val a = auth()
        assertEquals(AuthHeaders.None, a.headersFor("http://192.168.1.91:5000/api/events/1/snapshot.jpg"))
        assertEquals(AuthHeaders.None, a.headersFor("http://192.168.1.92:8971/x.jpg"))
        assertEquals(AuthHeaders.None, a.headersFor("https://192.168.1.91:8971/x.jpg"))
        assertEquals(AuthHeaders.None, a.headersFor("not a url"))
        assertTrue(posts.isEmpty())
        val plain = auth(config = FrigateConfig("http://frigate", "admin", "pw"))
        assertIs<AuthHeaders.Ready>(plain.headersFor("http://frigate:80/x.jpg"))
    }

    @Test
    fun `no config or no login means no cookie`(): Unit = runBlocking {
        assertEquals(AuthHeaders.None, auth(config = null).headersFor(snap))
        assertEquals(AuthHeaders.None, auth(config = FrigateConfig("http://192.168.1.91:8971")).headersFor(snap))
        assertEquals(AuthHeaders.None, auth(config = FrigateConfig("http://192.168.1.91:8971", "admin", null)).headersFor(snap))
        assertTrue(posts.isEmpty())
    }

    @Test
    fun `a refused login is AuthFailed`(): Unit = runBlocking {
        for (status in listOf(400, 401, 403)) {
            assertEquals(AuthHeaders.Failed(MediaFailure.AuthFailed), auth(answer = { HttpPostResult.Response(status, emptyMap()) }).headersFor(snap))
        }
    }

    @Test
    fun `a login answer without the cookie (gateway older than v1_4) is Unavailable, not AuthFailed`(): Unit = runBlocking {
        val r = assertIs<AuthHeaders.Failed>(auth(answer = { HttpPostResult.Response(200, emptyMap()) }).headersFor(snap))
        val failure = assertIs<MediaFailure.Unavailable>(r.failure)
        assertTrue("v1.4" in failure.reason)
    }

    @Test
    fun `rate limit, refused destination and no route map to their failures`(): Unit = runBlocking {
        assertIs<MediaFailure.Unavailable>(assertIs<AuthHeaders.Failed>(auth(answer = { HttpPostResult.Response(429, emptyMap()) }).headersFor(snap)).failure)
        assertEquals(AuthHeaders.Failed(MediaFailure.NotAllowed), auth(answer = { HttpPostResult.NotAllowed }).headersFor(snap))
        assertEquals(AuthHeaders.Failed(MediaFailure.Unavailable("no route")), auth(answer = { HttpPostResult.Unavailable("no route") }).headersFor(snap))
    }

    @Test
    fun `renew logs in again for a rejected token, but not when another caller already did`(): Unit = runBlocking {
        val a = auth()
        val first = assertIs<AuthHeaders.Ready>(a.headersFor(snap))
        val second = assertIs<AuthHeaders.Ready>(a.renew(snap, first.headers))
        assertEquals(mapOf("Cookie" to "frigate_token=t2"), second.headers)
        assertEquals(second, a.renew(snap, first.headers)) // stale rejection: reuse t2
        assertEquals(2, posts.size)
    }

    @Test
    fun `a changed config drops the cached token`(): Unit = runBlocking {
        var config = login
        val a = FrigateAuth(config = { config }, post = { url, json -> posts += url to json; HttpPostResult.Response(200, mapOf("set-cookie" to "frigate_token=t${posts.size}")) })
        a.headersFor(snap)
        config = login.copy(password = "new")
        assertEquals(AuthHeaders.Ready(mapOf("Cookie" to "frigate_token=t2")), a.headersFor(snap))
    }

    @Test
    fun `the token is found among several cookies, joined either way`() {
        assertEquals("jwt", FrigateAuth.tokenIn("a=1; Path=/\nfrigate_token=jwt; HttpOnly"))
        assertEquals("jwt", FrigateAuth.tokenIn("a=1; Path=/, frigate_token=jwt; HttpOnly"))
        assertEquals(null, FrigateAuth.tokenIn("a=1"))
        assertEquals(null, FrigateAuth.tokenIn(null))
    }
}
