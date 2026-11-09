package com.lagradost.cloudstream4.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `KMP-95e`: what a `cloudstreamapp://` redirect must carry to be taken, and that nothing else is. */
class BrowserRedirectsTest {
    private val redirects = BrowserRedirects()

    @Test
    fun `nothing is taken while no sign-in waits`() {
        assertFalse(redirects.deliver("cloudstreamapp://mallogin?code=c&state=s"))
    }

    @Test
    fun `only the waiting path, the cloudstreamapp scheme and the very state are taken, and only once`() = runBlocking {
        val waiting = redirects.expect("mallogin", "s1")
        listOf(
            "cloudstreamapp://anilistlogin?code=c&state=s1",
            "https://mallogin?code=c&state=s1",
            "cloudstreamapp://mallogin?code=c",
            "cloudstreamapp://mallogin?code=c&state=s",
            "cloudstreamapp://mallogin?code=c&state=s1x",
            "cloudstreamapp://mallogin?code=c&state=s1&state=s2",
            "cloudstreamapp://mallogin?state=s1&state=s1",
            "not a url at all",
        ).forEach { assertFalse(redirects.deliver(it), it) }
        val answer = async { waiting.await() }
        assertTrue(redirects.deliver("CloudStreamApp://mallogin?code=c%2B1&state=s1"))
        assertEquals(mapOf("code" to "c+1"), answer.await(), "decoded, and without the state")
        assertFalse(redirects.deliver("cloudstreamapp://mallogin?code=c&state=s1"), "once")
    }

    @Test
    fun `a cancelled wait takes nothing after it, and a new sign-in ends the one before`() = runBlocking {
        val first = redirects.expect("mallogin", "s1")
        first.cancel()
        assertFalse(redirects.deliver("cloudstreamapp://mallogin?code=c&state=s1"))
        assertFailsWith<CancellationException> { first.await() }

        val second = redirects.expect("mallogin", "s2")
        val third = redirects.expect("mallogin", "s3")
        yield()
        assertFailsWith<CancellationException> { second.await() }
        assertFalse(redirects.deliver("cloudstreamapp://mallogin?code=c&state=s2"), "the ended one's state")
        assertTrue(redirects.deliver("cloudstreamapp://mallogin?code=c&state=s3"))
        assertEquals("c", third.await()["code"])
    }

    @Test
    fun `a state and a verifier are fresh, url-safe and long enough`() {
        val states = List(50) { BrowserRedirects.newState() }
        val verifiers = List(50) { BrowserRedirects.newVerifier() }
        assertEquals(50, states.toSet().size)
        assertEquals(50, verifiers.toSet().size, "never reused")
        assertTrue(states.all { it.length == 43 && it.matches(Regex("[A-Za-z0-9_-]+")) })
        assertTrue(verifiers.all { it.length == 128 && it.matches(Regex("[A-Za-z0-9_-]+")) }, "RFC 7636: 43 to 128 unreserved characters")
    }

    @Test
    fun `a query that repeats a name or is too long is not read`() {
        assertNull(BrowserRedirects.parameters("a=1&a=2"))
        assertNull(BrowserRedirects.parameters("a=" + "x".repeat(9_000)))
        assertEquals(mapOf("a" to "1", "b" to ""), BrowserRedirects.parameters("a=1&b"))
    }
}
