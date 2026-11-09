package com.lagradost.cloudstream4.account

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `KMP-95d`: the Mac's sign-in exchanges, against a loopback server that answers as each service does. Every value is
 * made up. The real hosts are https; the server here is plain http on 127.0.0.1, which is all a test can reach.
 */
class HttpSignInClientTest {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply { start() }
    private val base = "http://127.0.0.1:${server.address.port}/"
    private val seen = mutableListOf<Pair<String, String>>()

    @AfterTest
    fun stop() = server.stop(0)

    private fun answer(path: String, status: Int, body: String, headers: Map<String, String> = emptyMap()) {
        server.createContext(path) { exchange ->
            seen += exchange.requestURI.toString() to exchange.requestBody.readBytes().decodeToString()
            headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            exchange.reply(status, body)
        }
    }

    private fun HttpExchange.reply(status: Int, body: String) {
        val bytes = body.toByteArray()
        sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) } else close()
    }

    private fun client(simkl: String? = "id0fixture", mal: String? = "mal0fixture", aniList: String? = "1234") = HttpSignInClient(
        simklClientId = simkl,
        malClientId = mal,
        aniListClientId = aniList,
        malSite = "${base}mal/".toHttpUrl(),
        simklApi = base.toHttpUrl(),
        openSubtitlesApi = "${base}os/".toHttpUrl(),
        subDlApi = "${base}subdl/".toHttpUrl(),
    )

    @Test
    fun `Simkl's code is read, and only a plain code and a simkl_com https page reach the screen`() = runBlocking {
        answer(
            "/oauth/pin",
            200,
            """{"result":"OK","device_code":"d","user_code":"FAKE00","verification_url":"https://simkl.com/pin","expires_in":900,"interval":5}""",
        )
        val code = assertIs<SignInResult.Done<PairCode>>(client().simklCode()).value
        assertEquals("FAKE00", code.userCode)
        assertEquals("https://simkl.com/pin", code.verificationUrl)
        assertEquals(900, code.expiresInSeconds)
        assertTrue(seen.single().first.contains("client_id=id0fixture"))

        assertEquals("https://simkl.com/pin", HttpSignInClient.simklVerificationUrl("https://evil.example/pin"))
        assertEquals("https://simkl.com/pin", HttpSignInClient.simklVerificationUrl("http://simkl.com/pin"))
        assertEquals("https://simkl.com/pin", HttpSignInClient.simklVerificationUrl("https://simkl.com.evil.example/pin"))
        assertEquals("https://simkl.com/pin", HttpSignInClient.simklVerificationUrl("https://user:pw@simkl.com/pin"))
        assertEquals("https://simkl.com/pin", HttpSignInClient.simklVerificationUrl(null))
    }

    @Test
    fun `a code that is not plain letters and digits is a failure, not shown`() = runBlocking {
        answer("/oauth/pin", 200, """{"user_code":"<b>FAKE</b>","verification_url":"https://simkl.com/pin","expires_in":900,"interval":5}""")
        assertEquals(SignInResult.Failed, client().simklCode())
    }

    @Test
    fun `without a client id nothing is sent`() = runBlocking {
        assertEquals(SignInResult.Failed, client(simkl = null).simklCode())
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a poll is pending until Simkl answers with a token`() = runBlocking {
        var approved = false
        server.createContext("/oauth/pin/FAKE00") { exchange ->
            exchange.reply(200, if (approved) """{"result":"OK","access_token":"tok"}""" else """{"result":"KO","message":"Authorization pending"}""")
        }
        val code = PairCode("FAKE00", "https://simkl.com/pin", 900, 5)
        assertEquals(SignInResult.Pending, client().simklPoll(code))
        approved = true
        assertEquals("tok", assertIs<SignInResult.Done<Credential>>(client().simklPoll(code)).value.token)
    }

    @Test
    fun `OpenSubtitles sends its key, keeps the password beside the token, and a 401 is a refusal`() = runBlocking {
        var key: String? = null
        server.createContext("/os/login") { exchange ->
            key = exchange.requestHeaders.getFirst("Api-Key")
            val body = exchange.requestBody.readBytes().decodeToString()
            if ("pw-right" in body) exchange.reply(200, """{"token":"jwt","user":{}}""") else exchange.reply(401, """{"message":"bad"}""")
        }
        val kept = assertIs<SignInResult.Done<Credential>>(client().passwordSignIn(DesktopService.OpenSubtitles, "robin", "pw-right")).value
        assertEquals("jwt", kept.token)
        assertEquals("pw-right", kept.password, "kept to renew the token (the owner's ruling)")
        assertTrue(key.orEmpty().isNotEmpty())
        assertEquals(SignInResult.Refused, client().passwordSignIn(DesktopService.OpenSubtitles, "robin", "pw-wrong"))
    }

    @Test
    fun `SubDL trades the login for its API key and keeps no password`() = runBlocking {
        answer("/subdl/login", 200, """{"token":"t1","status":true}""")
        var bearer: String? = null
        server.createContext("/subdl/user/userApi") { exchange ->
            bearer = exchange.requestHeaders.getFirst("Authorization")
            exchange.reply(200, """{"ok":true,"api_key":"key1"}""")
        }
        val kept = assertIs<SignInResult.Done<Credential>>(client().passwordSignIn(DesktopService.SubDl, "robin@example.com", "pw")).value
        assertEquals("key1", kept.token)
        assertEquals("robin@example.com", kept.email)
        assertNull(kept.password)
        assertEquals("Bearer t1", bearer)
    }

    @Test
    fun `a token with CR, LF or non-ASCII is a failure, never a header or a stored sign-in`() = runBlocking {
        // SubDL's login token goes into the next request's Authorization header (the reviewer of #290).
        answer("/subdl/login", 200, """{"token":"t1\r\nX-Injected: 1","status":true}""")
        var keyAsked = false
        server.createContext("/subdl/user/userApi") { exchange ->
            keyAsked = true
            exchange.reply(200, """{"ok":true,"api_key":"key1"}""")
        }
        assertEquals(SignInResult.Failed, client().passwordSignIn(DesktopService.SubDl, "robin@example.com", "pw"))
        assertTrue(!keyAsked, "nothing was sent with it")

        var approvedWith = "tok\\nen" // a JSON escape: the token Simkl sends holds a real LF
        server.createContext("/oauth/pin/FAKE00") { exchange -> exchange.reply(200, """{"result":"OK","access_token":"$approvedWith"}""") }
        val code = PairCode("FAKE00", "https://simkl.com/pin", 900, 5)
        assertEquals(SignInResult.Failed, client().simklPoll(code), "Simkl's token is checked before it is kept")
        approvedWith = "tökén"
        assertEquals(SignInResult.Failed, client().simklPoll(code))

        listOf("abc.DEF-123_~+/=", "a".repeat(4096)).forEach { assertTrue(HttpSignInClient.isToken(it), it.take(20)) }
        listOf("", "a b", "a\rb", "a\nb", "é", "a".repeat(4097)).forEach { assertTrue(!HttpSignInClient.isToken(it), it.take(20)) }
    }

    @Test
    fun `MyAnimeList's page carries the client, the state and a plain PKCE challenge, and none without a key`() {
        val page = assertNotNull(client().malSignInPage(state = "st4te", verifier = "v".repeat(128))).toHttpUrl()
        assertEquals("code", page.queryParameter("response_type"))
        assertEquals("mal0fixture", page.queryParameter("client_id"))
        assertEquals("st4te", page.queryParameter("state"))
        assertEquals("v".repeat(128), page.queryParameter("code_challenge"))
        assertEquals("plain", page.queryParameter("code_challenge_method"))
        assertNull(client(mal = null).malSignInPage("s", "v"))
    }

    @Test
    fun `AniList's page is its implicit grant, with the state, and none without a key`() {
        val page = assertNotNull(client().aniListSignInPage(state = "st4te")).toHttpUrl()
        assertEquals("anilist.co", page.host)
        assertEquals("/api/v2/oauth/authorize", page.encodedPath)
        assertEquals("1234", page.queryParameter("client_id"))
        assertEquals("token", page.queryParameter("response_type"))
        assertEquals("st4te", page.queryParameter("state"))
        assertNull(client(aniList = null).aniListSignInPage("s"))
    }

    @Test
    fun `MyAnimeList's code is traded with its verifier, a 400 is a refusal, and a code that is not a token sends nothing`() = runBlocking {
        server.createContext("/mal/v1/oauth2/token") { exchange ->
            val form = exchange.requestBody.readBytes().decodeToString()
            seen += exchange.requestURI.toString() to form
            if ("code=good" in form) exchange.reply(200, """{"token_type":"Bearer","expires_in":2678400,"access_token":"at1","refresh_token":"rt1"}""")
            else exchange.reply(400, """{"error":"invalid_grant"}""")
        }
        val kept = assertIs<SignInResult.Done<Credential>>(client().malExchange("good", "ver1fier")).value
        assertEquals("at1", kept.token)
        assertEquals("rt1", kept.refreshToken)
        val form = seen.single().second
        listOf("client_id=mal0fixture", "code=good", "code_verifier=ver1fier", "grant_type=authorization_code").forEach { assertTrue(it in form, it) }
        assertEquals(SignInResult.Refused, client().malExchange("bad", "v"))
        seen.clear()
        assertEquals(SignInResult.Failed, client().malExchange("bad\r\ncode", "v"))
        assertTrue(seen.isEmpty(), "nothing was sent")
    }

    @Test
    fun `a redirect is not followed, a huge or unreadable answer fails, and a closed port is offline`() = runBlocking {
        answer("/os/login", 302, "", mapOf("Location" to "${base}elsewhere"))
        answer("/elsewhere", 200, """{"token":"jwt"}""")
        assertEquals(SignInResult.Failed, client().passwordSignIn(DesktopService.OpenSubtitles, "robin", "pw"))
        assertTrue(seen.none { it.first == "/elsewhere" }, "only the service's own host is reached")

        answer("/oauth/pin", 200, "x".repeat(70 * 1024))
        assertEquals(SignInResult.Failed, client().simklCode())

        answer("/subdl/login", 200, "not json")
        assertEquals(SignInResult.Failed, client().passwordSignIn(DesktopService.SubDl, "robin@example.com", "pw"))

        val closed = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        val offline = HttpSignInClient(simklClientId = "id", simklApi = "http://127.0.0.1:$closed/".toHttpUrl())
        assertEquals(SignInResult.Offline, offline.simklCode())
    }
}
