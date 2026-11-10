package com.lagradost.cloudstream4.extensions.repositories

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.Collections
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * `KMP-110`: a whole repository read. The fetches are answered from a map, since the reader only ever asks for https
 * and a TLS test server would be a new dependency; [RepositoryHttpTest] covers the client itself. Every repository and
 * plugin here is made up.
 */
class RepositoryReaderTest {
    private val files = mutableMapOf<String, Fetched>()
    private val asked = Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
    private val reader = RepositoryReader { url, maxBytes ->
        asked += url to maxBytes
        files[url] ?: Fetched.Failed(RepositoryFailure.NotFound)
    }

    private fun bytes(text: String) = Fetched.Bytes(text.toByteArray())

    private fun plugin(internalName: String) =
        """{"internalName":"$internalName","name":"$internalName","url":"https://files.example/${internalName.replace(" ", "%20")}.cs3",""" +
            """"version":1,"status":1,"apiVersion":1,"authors":[]}"""

    @Test
    fun `every list is read, and an unreadable one is counted rather than read as empty`() = runBlocking {
        files[REPO] = bytes(
            """{"name":"Harbour Lights","manifestVersion":1,"pluginLists":["$LIST/a.json","$LIST/b.json","$LIST/c.json","$LIST/d.json"]}""",
        )
        files["$LIST/a.json"] = bytes("[${plugin("Lanternfish")},${plugin("Mooring")}]")
        files["$LIST/b.json"] = bytes("[${plugin("Mooring")},${plugin("Salt Road")},7]")
        files["$LIST/c.json"] = bytes("{\"not\":\"a list\"}")
        // d.json is not there.
        val read = assertIs<RepositoryRead.Read>(reader.read(REPO))
        assertEquals("Harbour Lights", read.index.name)
        assertEquals(listOf("Lanternfish", "Mooring", "Salt Road"), read.listings.map { it.internalName })
        assertEquals(2, read.unreadableLists)
        assertEquals(2, read.droppedEntries, "the 7, and Mooring's second listing")
    }

    @Test
    fun `each file is asked for with its own cap`() = runBlocking {
        files[REPO] = bytes("""{"name":"Harbour Lights","manifestVersion":1,"pluginLists":["$LIST/a.json"]}""")
        files["$LIST/a.json"] = bytes("[]")
        reader.read(REPO)
        assertEquals(
            listOf(REPO to RepositoryLimits.MAX_REPOSITORY_BYTES, "$LIST/a.json" to RepositoryLimits.MAX_PLUGIN_LIST_BYTES),
            asked.toList(),
        )
    }

    @Test
    fun `a repository that cannot be read says why`() = runBlocking {
        assertEquals(RepositoryRead.Failed(RepositoryFailure.NotFound), reader.read(REPO))
        files[REPO] = Fetched.Failed(RepositoryFailure.PrivateAddress)
        assertEquals(RepositoryRead.Failed(RepositoryFailure.PrivateAddress), reader.read(REPO))
        files[REPO] = bytes("<html>a sign-in page</html>")
        assertEquals(RepositoryRead.Failed(RepositoryFailure.NotARepository), reader.read(REPO))
        files[REPO] = bytes("""{"name":"Harbour Lights","manifestVersion":7,"pluginLists":[]}""")
        assertEquals(RepositoryRead.Failed(RepositoryFailure.UnsupportedFormat), reader.read(REPO))
    }

    @Test
    fun `a failure prints nothing, so a token in the link goes nowhere`() = runBlocking {
        val token = "tok_made_up_8f2c"
        val out = ByteArrayOutputStream()
        val (stdout, stderr) = System.out to System.err
        System.setOut(PrintStream(out, true))
        System.setErr(PrintStream(out, true))
        val result = try {
            reader.read("$REPO?token=$token")
            // The real client, refusing an address: the path most likely to carry a URL in an exception.
            RepositoryReader().read("https://127.0.0.1:9/repo.json?token=$token")
        } finally {
            System.setOut(stdout)
            System.setErr(stderr)
        }
        assertEquals(RepositoryRead.Failed(RepositoryFailure.PrivateAddress), result)
        assertFalse(out.toString().contains(token), "printed: $out")
        assertFalse(out.toString().contains("127.0.0.1"), "printed: $out")
    }

    private companion object {
        const val REPO = "https://files.example/harbour/repo.json"
        const val LIST = "https://files.example/harbour"
    }
}
