package com.lagradost.cloudstream4.extensions.repositories

import com.lagradost.cloudstream4.feature.extensions.ExtensionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `KMP-110`: a repository's JSON read as untrusted input. Every repository and plugin here is made up. */
class RepositoryIndexParserTest {
    private fun repository(json: String) = RepositoryIndexParser.repository(json.toByteArray())

    private fun list(json: String) = checkNotNull(RepositoryIndexParser.pluginList(json.toByteArray())) { "not a list" }

    private fun plugin(
        internalName: String = "Lanternfish",
        extra: String = "",
        url: String = "\"https://files.example/lanternfish.cs3\"",
        version: String = "4",
        status: String = "1",
    ) = """{"internalName":${quote(internalName)},"name":"Lanternfish","url":$url,"version":$version,"status":$status,""" +
        """"apiVersion":1,"authors":["Tamsin Vale"]$extra}"""

    /** [text] as a JSON string, control characters escaped as a server would send them. */
    private fun quote(text: String) = buildString {
        append('"')
        text.forEach { char ->
            when {
                char == '\\' || char == '"' -> append('\\').append(char)
                char.isISOControl() -> append("\\u").append(char.code.toString(16).padStart(4, '0'))
                else -> append(char)
            }
        }
        append('"')
    }

    @Test
    fun `a repository is read into the shared contract`() {
        val read = assertIs<ParsedRepository.Read>(
            repository(
                """{"name":"Harbour Lights","description":"Made up","manifestVersion":1,""" +
                    """"pluginLists":["https://files.example/plugins.json"],"somethingNew":true}""",
            ),
        )
        assertEquals("Harbour Lights", read.index.name)
        assertEquals("Made up", read.index.description)
        assertEquals(listOf("https://files.example/plugins.json"), read.index.pluginListUrls)
        assertEquals(0, read.droppedLists)
    }

    @Test
    fun `manifest versions 1 and 2 are read, and others are refused`() {
        fun version(v: String) = repository("""{"name":"Harbour Lights","manifestVersion":$v,"pluginLists":[]}""")
        assertIs<ParsedRepository.Read>(version("1"))
        assertIs<ParsedRepository.Read>(version("2"))
        assertEquals(ParsedRepository.Refused(RepositoryFailure.UnsupportedFormat), version("3"))
        assertEquals(ParsedRepository.Refused(RepositoryFailure.NotARepository), version("\"1\""))
    }

    @Test
    fun `anything that is not a repository is said to be one`() {
        listOf(
            "", "[]", "null", "{", "<html>not json</html>",
            """{"manifestVersion":1,"pluginLists":[]}""",
            """{"name":"","manifestVersion":1,"pluginLists":[]}""",
            """{"name":"Harbour Lights","pluginLists":[]}""",
            """{"name":"Harbour Lights","manifestVersion":1}""",
            """{"name":"Harbour Lights","manifestVersion":1,"pluginLists":"https://files.example/p.json"}""",
        ).forEach { json ->
            assertEquals(ParsedRepository.Refused(RepositoryFailure.NotARepository), repository(json), json)
        }
    }

    @Test
    fun `plugin lists that are not https, or past the limit, are left out`() {
        // Written as JSON, not quoted: a number, and a control character escaped the way a server would send it.
        val raw = listOf("42", "\"https://files.example/c\\u0000.json\"")
        val urls = listOf(
            "http://files.example/a.json", "https://user:secret@files.example/b.json", "file:///etc/hosts",
            "https://files.example/" + "d".repeat(MAX_LINK_CHARS),
        ).map(::quote) + raw + (1..20).map { quote("https://files.example/list$it.json") }
        val json = urls.joinToString(",")
        val read = assertIs<ParsedRepository.Read>(repository("""{"name":"Harbour Lights","manifestVersion":1,"pluginLists":[$json]}"""))
        assertEquals((1..16).map { "https://files.example/list$it.json" }, read.index.pluginListUrls)
        assertEquals(urls.size - 16, read.droppedLists)
    }

    @Test
    fun `a plugin is read into the shared contract`() {
        val hash = "sha256-" + "ab".repeat(32)
        val listing = list(
            "[" + plugin(
                extra = ""","description":"Tide tables","language":"en","tvTypes":["Movie"],"fileSize":2048,""" +
                    """"fileHash":"$hash","jarUrl":"https://files.example/lanternfish.jar","jarHash":"$hash","jarFileSize":1024""",
            ) + "]",
        ).listings.single()
        assertEquals("Lanternfish", listing.internalName)
        assertEquals("https://files.example/lanternfish.cs3", listing.downloadUrl)
        assertEquals(ExtensionStatus.Ok, listing.status)
        assertEquals(4, listing.version)
        assertEquals(listOf("Tamsin Vale"), listing.authors)
        assertEquals(2048L, listing.fileSizeBytes)
        assertEquals(hash, listing.fileHash)
        assertEquals("https://files.example/lanternfish.jar", listing.jarUrl)
        assertEquals(1024L, listing.jarFileSizeBytes)
    }

    @Test
    fun `one bad entry costs that entry, not the list`() {
        val read = list(
            "[" + listOf(
                plugin(internalName = "Good"),
                "42",
                plugin(internalName = "NoUrl", url = "null"),
                plugin(internalName = "HttpUrl", url = "\"http://files.example/x.cs3\""),
                plugin(internalName = "ZeroVersion", version = "0"),
                plugin(internalName = "StringVersion", version = "\"4\""),
                plugin(internalName = "FractionVersion", version = "4.5"),
                plugin(internalName = "NoStatus", status = "null"),
                plugin(internalName = ""),
                plugin(internalName = "Tab\tName"),
                plugin(internalName = "x".repeat(129)),
                plugin(internalName = "BadHash", extra = ""","fileHash":12"""),
                plugin(internalName = "LongHash", extra = ""","fileHash":"sha256-${"a".repeat(200)}""""),
                plugin(internalName = "BadJarHash", extra = ""","jarHash":["sha256-x"]"""),
            ).joinToString(",") + "]",
        )
        assertEquals(listOf("Good"), read.listings.map { it.internalName })
        assertEquals(13, read.dropped)
    }

    @Test
    fun `an identity with a space or a plus is kept, since it is never a file name`() {
        val names = list("[" + plugin(internalName = "18+") + "," + plugin(internalName = "Salt Road") + "]").listings
        assertEquals(listOf("18+", "Salt Road"), names.map { it.internalName })
    }

    @Test
    fun `always-update and unknown statuses are kept as they are`() {
        val listings = list("[" + plugin(internalName = "A", version = "-1") + "," + plugin(internalName = "B", status = "9") + "]").listings
        assertEquals(RepositoryIndexParser.ALWAYS_UPDATE, listings[0].version)
        assertEquals(ExtensionStatus.Unrecognised(9), listings[1].status)
    }

    @Test
    fun `the same internal name twice keeps the first`() {
        val read = list("[" + plugin(version = "1") + "," + plugin(version = "2") + "]")
        assertEquals(listOf(1), read.listings.map { it.version })
        assertEquals(1, read.dropped)
    }

    @Test
    fun `display text is cut to its limit, and optional URLs that fail are left out`() {
        val listing = list(
            "[" + plugin(
                extra = ""","description":"${"d".repeat(10_000)}","authors":["${"a".repeat(500)}","\u0007Bell",3],""" +
                    """"iconUrl":"http://files.example/i.png","repositoryUrl":"javascript:alert(1)","language":"${"l".repeat(40)}"""",
            ) + "]",
        ).listings.single()
        assertEquals(RepositoryLimits.MAX_DESCRIPTION_CHARS, listing.description?.length)
        assertEquals(listOf("a".repeat(RepositoryLimits.MAX_NAME_CHARS), "Bell"), listing.authors)
        assertNull(listing.iconUrl)
        assertNull(listing.repositoryUrl)
        assertNull(listing.language)
    }

    @Test
    fun `a list longer than the limit is cut`() {
        val json = (0 until RepositoryLimits.MAX_PLUGINS_PER_LIST + 5).joinToString(",", "[", "]") { plugin(internalName = "P$it") }
        val read = list(json)
        assertEquals(RepositoryLimits.MAX_PLUGINS_PER_LIST, read.listings.size)
        assertEquals(5, read.dropped)
    }

    @Test
    fun `what is not a list is not read, and deep nesting is not a crash`() {
        assertNull(RepositoryIndexParser.pluginList("{}".toByteArray()))
        assertNull(RepositoryIndexParser.pluginList("not json".toByteArray()))
        val deep = "[".repeat(200_000) + "]".repeat(200_000)
        val read = RepositoryIndexParser.pluginList(deep.toByteArray())
        assertTrue(read == null || read.listings.isEmpty())
        assertIs<ParsedRepository.Refused>(RepositoryIndexParser.repository(deep.toByteArray()))
    }
}
