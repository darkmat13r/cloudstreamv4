package com.lagradost.cloudstream4.feature.extensions.sandbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `BUG-83`: a plugin's own text never reaches a default output, and the developer's copy of it is redacted. */
class PluginOutputTest {

    // ---- redactedPluginText: the one set of rules -----------------------------------------------------------

    @Test
    fun `a url keeps its scheme and host, never its user info, path, query or fragment`() {
        assertEquals(
            "fetch https://media.example:8443/<…> now",
            "fetch https://viewer:hunter2@media.example:8443/watch/SECRET_PATH?q=SECRET_QUERY#SECRET_FRAG now"
                .redactedPluginText(),
        )
    }

    @Test
    fun `a run of 24 token characters is a token, and one of 23 is not`() {
        val twentyThree = "Zq8Xv3Lm9Pt2Rk7Wd4Hs6Nb"
        val twentyFour = twentyThree + "1"
        assertEquals(23, twentyThree.length)
        assertEquals("key $twentyThree end", "key $twentyThree end".redactedPluginText())
        assertEquals("key <…> end", "key $twentyFour end".redactedPluginText())
    }

    @Test
    fun `base64 with its padding goes whole`() {
        assertEquals("blob <…> done", "blob U2FtcGxlU2VjcmV0VmFsdWVGb3JUZXN0cw== done".redactedPluginText())
    }

    @Test
    fun `a path under the home directory goes, written either way`() {
        assertEquals("cache <path>", "cache ~/Library/plugin-cache/state.json".redactedPluginText())
        assertEquals("cache at <path>", "cache at /Users/sample-user/Library/plugin-cache".redactedPluginText())
    }

    @Test
    fun `an email address goes`() {
        assertEquals("signed in as <email>.", "signed in as viewer.one@mail.example.".redactedPluginText())
    }

    @Test
    fun `an ip literal goes, and a time of day does not`() {
        assertEquals("peer <ip> port 80", "peer 192.0.2.44 port 80".redactedPluginText())
        assertEquals("peer <ip> and <ip>", "peer 2001:db8::7 and fe80:0:0:0:1:2:3:4".redactedPluginText())
        assertEquals("at 19:04:05 it ended", "at 19:04:05 it ended".redactedPluginText())
    }

    @Test
    fun `a stack frame and an ordinary sentence stay as they are`() {
        val frame = "\tat com.example.plugin.SearchApi.search(SearchApi.kt:42)"
        assertEquals(frame.replace('\t', ' '), frame.redactedPluginText())
        assertEquals("no results for that page", "no results for that page".redactedPluginText())
    }

    // ---- redactedWithin and PluginFailed: redacted, then cut (#335 review) --------------------------------------

    /** "IllegalStateException: " and words, then [secret] starting [before] characters before the 400th. */
    private fun straddling(secret: String, before: Int): String {
        val head = "IllegalStateException: "
        val room = 400 - before - head.length
        val words = "word ".repeat(room / 5 + 1).take(room - 1).trimEnd().padEnd(room)
        return head + words + secret + " and more words after it"
    }

    private val token30 = "Zq8Xv3Lm9Pt2Rk7Wd4Hs6Nb1Yc5Gf0"

    @Test
    fun `a token across the cut at 400 is redacted, not cut short of its rule`() {
        val text = straddling(token30, before = 15)
        assertEquals(400 - 15, text.indexOf(token30))
        listOf(text.redactedWithin(400), PluginFailed(text).message.orEmpty()).forEach { message ->
            assertTrue(message.length <= 400, "${message.length}")
            assertFalse(token30.take(10) in message, message)
        }
    }

    @Test
    fun `an address across the cut at 400 is redacted, not cut short of its rule`() {
        val text = straddling("192.0.2.44", before = 5)
        assertEquals(400 - 5, text.indexOf("192.0.2.44"))
        listOf(text.redactedWithin(400), PluginFailed(text).message.orEmpty()).forEach { message ->
            assertTrue(message.length <= 400, "${message.length}")
            assertFalse("192.0" in message, message)
        }
    }

    @Test
    fun `a control character inside a token does not rejoin it once removed`() {
        val split = "Zq8Xv3Lm9Pt2Rk\nWd4Hs6Nb1Yc5G"
        val joined = split.replace("\n", "")
        assertFalse(joined in PluginFailed("IllegalStateException: $split").message.orEmpty())
        val stderr = HostStderr()
        val bytes = "key ${split.replace('\n', '\r')} end\n".toByteArray()
        stderr.feed(bytes, bytes.size)
        stderr.finish()
        assertFalse(joined in stderr.redactedLog, stderr.redactedLog)
    }

    // ---- HostStderr: what is kept of a host's stderr ---------------------------------------------------------

    private fun HostStderr.write(text: String, chunk: Int = text.length) {
        text.toByteArray().toList().chunked(chunk).forEach { part ->
            val bytes = part.toByteArray()
            feed(bytes, bytes.size)
        }
    }

    private val leaked = listOf(
        "SECRET_QUERY", "hunter2", "Zq8Xv3Lm9Pt2Rk7Wd4Hs6Nb1Yc", "U2FtcGxl", "sample-user", "viewer.one", "192.0.2.44",
    )

    private val pluginLines = "GET https://viewer:hunter2@media.example/watch?q=SECRET_QUERY\n" +
        "token Zq8Xv3Lm9Pt2Rk7Wd4Hs6Nb1Yc blob U2FtcGxlU2VjcmV0VmFsdWVGb3JUZXN0cw==\n" +
        "home /Users/sample-user/Library/x\n" +
        "mail viewer.one@mail.example from 192.0.2.44\n"

    @Test
    fun `the summary holds counts and the host's own failure class, and none of the plugin's text`() {
        val stderr = HostStderr()
        stderr.write(pluginLines + HostStderr.FAILURE_PREFIX + "java.lang.IllegalStateException\n")
        stderr.finish()
        assertEquals(
            "stderr: 5 lines, ${(pluginLines.length + HostStderr.FAILURE_PREFIX.length + 32)} bytes" +
                " · failed: java.lang.IllegalStateException",
            stderr.summary,
        )
        leaked.forEach { assertFalse(it in stderr.summary, it) }
    }

    @Test
    fun `the developer's log is redacted, line by line`() {
        val stderr = HostStderr()
        stderr.write(pluginLines)
        stderr.finish()
        leaked.forEach { assertFalse(it in stderr.redactedLog, "$it in ${stderr.redactedLog}") }
        assertTrue("https://media.example/<…>" in stderr.redactedLog, stderr.redactedLog)
    }

    @Test
    fun `a url split between two reads is still redacted`() {
        val line = "GET https://media.example/watch?q=SECRET_QUERY now\n"
        // Every split point, including inside "://" and inside the query.
        for (split in 1 until line.length) {
            val stderr = HostStderr()
            val bytes = line.toByteArray()
            stderr.feed(bytes.copyOfRange(0, split), split)
            stderr.feed(bytes.copyOfRange(split, bytes.size), bytes.size - split)
            stderr.finish()
            assertEquals("GET https://media.example/<…> now\n", stderr.redactedLog, "split at $split")
        }
    }

    @Test
    fun `a forged failure line is not a marker unless it is exactly a class name of at most 128`() {
        val forged = listOf(
            "https://media.example/?k=SECRET_QUERY",
            "a/b",
            "a:b",
            // 129 characters: one past the limit.
            "java.lang." + "Failed".repeat(19) + "Excep",
        )
        forged.forEach { name ->
            val stderr = HostStderr()
            stderr.write(HostStderr.FAILURE_PREFIX + name + "\n")
            stderr.finish()
            assertFalse("failed:" in stderr.summary, "$name gave ${stderr.summary}")
        }
        val atTheLimit = "java.lang." + "Failed".repeat(19) + "Exce"
        assertEquals(128, atTheLimit.length)
        val longest = HostStderr()
        longest.write(HostStderr.FAILURE_PREFIX + atTheLimit + "\n")
        longest.finish()
        assertTrue("failed:" in longest.summary, longest.summary)
    }

    @Test
    fun `a forged marker that is a token is cut from the summary too`() {
        val stderr = HostStderr()
        stderr.write(HostStderr.FAILURE_PREFIX + "Zq8Xv3Lm9Pt2Rk7Wd4Hs6Nb1Yc5Gf0Ja\n")
        stderr.finish()
        assertEquals("stderr: 1 lines, 54 bytes · failed: <…>", stderr.summary)
    }

    @Test
    fun `a long exception name is kept in the summary, as it reads as words`() {
        val stderr = HostStderr()
        stderr.write(HostStderr.FAILURE_PREFIX + "java.lang.ArrayIndexOutOfBoundsException\n")
        stderr.finish()
        assertTrue(stderr.summary.endsWith(" · failed: java.lang.ArrayIndexOutOfBoundsException"), stderr.summary)
    }

    @Test
    fun `a line past the cut keeps only its words before it`() {
        val stderr = HostStderr()
        stderr.write("start " + "x".repeat(10_000) + "Zq8Xv3Lm9Pt2\n", chunk = 1000)
        stderr.finish()
        assertEquals("start <…>\n", stderr.redactedLog)
    }

    @Test
    fun `the developer's log stops at its limit, and the counts do not`() {
        val stderr = HostStderr(maxLogChars = 100)
        repeat(50) { stderr.write("line $it of plain words\n") }
        stderr.finish()
        assertTrue(stderr.redactedLog.length <= 101, "${stderr.redactedLog.length}")
        assertTrue(stderr.summary.startsWith("stderr: 50 lines,"), stderr.summary)
    }
}
