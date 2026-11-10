package com.lagradost.cloudstream4.feature.extensions.sandbox

import com.lagradost.cloudstream4.feature.extensions.sandbox.TitleSectionFrames.page
import com.lagradost.cloudstream4.feature.extensions.sandbox.TitleSectionFrames.person
import com.lagradost.cloudstream4.feature.extensions.sandbox.TitleSectionFrames.rawText
import com.lagradost.cloudstream4.feature.extensions.sandbox.TitleSectionFrames.received
import com.lagradost.cloudstream4.feature.extensions.sandbox.TitleSectionFrames.refusedImages
import com.lagradost.cloudstream4.feature.extensions.sandbox.TitleSectionFrames.reply
import com.lagradost.cloudstream4.feature.extensions.sandbox.TitleSectionFrames.section
import com.lagradost.cloudstream4.feature.extensions.sandbox.TitleSectionFrames.suggestion
import java.io.DataOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A title's cast on the wire (`KMP-103`), read as hostile. Frames are written by hand, as a lying host would. */
class HostProtocolCastTest {

    private fun cast(body: DataOutputStream.() -> Unit) = reply { section(HostProtocol.CAST_V1, body) }

    private fun people(vararg write: DataOutputStream.() -> Unit) = cast {
        writeInt(write.size)
        write.forEach { it() }
    }

    private fun castOf(frame: ByteArray, dropped: MutableList<String> = ArrayList()) = received(frame, dropped).cast

    @Test
    fun `a cast survives the round trip, and none stays none`() {
        val details = page.copy(
            cast = listOf(
                RemoteCastMember("Ines Marlowe", RemoteActorRole.Main, "Cdr. Rhea Voss", null, "https://img.example/i.jpg"),
                RemoteCastMember("Ren Takeda", RemoteActorRole.Supporting, null, "Hana Sato", null),
                RemoteCastMember("Noor Aziz"),
            ),
        )
        assertEquals(details, received(HostProtocol.encode { writeDetails(details) }))
        assertEquals(page, received(HostProtocol.encode { writeDetails(page) }))
    }

    @Test
    fun `a reply from before the cast section opens the page with no cast`() {
        val dropped = ArrayList<String>()
        val details = received(reply { }, dropped)
        assertEquals(page, details)
        assertEquals(emptyList(), dropped)
    }

    @Test
    fun `a later cast version and a second cast section are stepped over whole`() {
        val dropped = ArrayList<String>()
        val frame = reply {
            section(HostProtocol.CAST_V1 + 1) { writeInt(1); person("From the future") }
            section(HostProtocol.CAST_V1) { writeInt(1); person("Ines Marlowe") }
            section(HostProtocol.CAST_V1) { writeInt(1); person("A second section") }
        }
        assertEquals(listOf("Ines Marlowe"), castOf(frame, dropped).map { it.name })
        assertEquals(listOf("a section this app does not read"), dropped, "said once")
    }

    @Test
    fun `twenty-four people are kept, and the rest of a count of a million is left unread`() {
        val dropped = ArrayList<String>()
        val frame = cast {
            writeInt(1_000_000)
            repeat(HostProtocol.MAX_CAST) { person("Person $it") }
        }
        val cast = castOf(frame, dropped)
        assertEquals(HostProtocol.MAX_CAST, cast.size)
        assertEquals("Person 23", cast.last().name)
        assertEquals(listOf("cast: entries past the count"), dropped)
    }

    @Test
    fun `a name of 256 bytes is read, one of 257 is skipped unread and the next person is still in step`() {
        val dropped = ArrayList<String>()
        val frame = people(
            { person("é".repeat(HostProtocol.MAX_PERSON_BYTES / 2)) },
            { rawText(ByteArray(HostProtocol.MAX_PERSON_BYTES + 1) { 'x'.code.toByte() }); writeByte(0); writeOptional(null); writeOptional(null); writeOptional(null) },
            { person("After it") },
        )
        val cast = castOf(frame, dropped)
        assertEquals(listOf("After it"), cast.map { it.name }, "128 é are 256 bytes but 128 characters, past 64")
        assertTrue("cast: name past its bytes" in dropped)
    }

    @Test
    fun `words lose invisible characters and are dropped past 64 or blank, never shortened`() {
        val frame = people(
            { person("‮In‍es Mar\u001B[2Jlowe ", role = "Cdr.\nRhea Voss") },
            { person("x".repeat(HostProtocol.MAX_PERSON_CHARS), role = "y".repeat(HostProtocol.MAX_PERSON_CHARS + 1)) },
            { person("x".repeat(HostProtocol.MAX_PERSON_CHARS + 1)) },
            { person(" ​ ") },
            { person("Noor", voice = "‍") },
        )
        val cast = castOf(frame)
        assertEquals(listOf("Ines Mar[2Jlowe", "x".repeat(HostProtocol.MAX_PERSON_CHARS), "Noor"), cast.map { it.name })
        assertEquals("Cdr.RheaVoss", cast[0].role)
        assertNull(cast[1].role, "a role past 64 costs only the role")
        assertNull(cast[2].voiceActor, "a blank voice actor is none")
        cast.forEach { person ->
            listOfNotNull(person.name, person.role, person.voiceActor).forEach { words ->
                assertTrue(words.none { it.isISOControl() || it.category == CharCategory.FORMAT }, "invisible text in $words")
            }
        }
    }

    @Test
    fun `the same person is kept once, compared once cleaned`() {
        val cast = castOf(
            people(
                { person("Ines Marlowe", role = "Voss") },
                { person("Ines‍ Marlowe", role = "Voss ") },
                { person("Ines Marlowe", role = "Pell") },
            ),
        )
        assertEquals(listOf("Voss", "Pell"), cast.map { it.role })
    }

    @Test
    fun `bytes that are not UTF-8 cost the field they are in, never a replacement character`() {
        val dropped = ArrayList<String>()
        val bad = byteArrayOf(0xC3.toByte(), 0x28)
        val frame = people(
            { rawText(bad); writeByte(0); writeOptional(null); writeOptional(null); writeOptional(null) },
            { writeText("Noor"); writeByte(0); writeBoolean(true); rawText(bad); writeOptional(null); writeOptional(null) },
        )
        val cast = castOf(frame, dropped)
        assertEquals(listOf(RemoteCastMember("Noor")), cast)
        assertTrue("cast: name not UTF-8" in dropped && "cast: role not UTF-8" in dropped)
    }

    @Test
    fun `a role kind is a code, and an unknown one costs only itself`() {
        val cast = castOf(
            people(
                { person("A", kind = HostProtocol.ROLE_MAIN) },
                { person("B", kind = HostProtocol.ROLE_SUPPORTING) },
                { person("C", kind = HostProtocol.ROLE_BACKGROUND) },
                { person("D", kind = 9) },
            ),
        )
        assertEquals(listOf(RemoteActorRole.Main, RemoteActorRole.Supporting, RemoteActorRole.Background, null), cast.map { it.roleKind })
        assertEquals("D", cast[3].name)
    }

    @Test
    fun `only a public https photo is kept, and a refused one costs only the photo`() {
        assertEquals("https://img.example/a.jpg", castOf(people({ person("A", image = "https://img.example/a.jpg") })).single().imageUrl)
        refusedImages.forEach { (url, what) ->
            val person = castOf(people({ person("A", image = url) })).singleOrNull()
            assertEquals("A", person?.name, "$what: the person stays")
            assertNull(person?.imageUrl, "$what must not reach the image loader")
        }
    }

    @Test
    fun `a section past 64 KiB is stepped over unread, and costs only the cast`() {
        val dropped = ArrayList<String>()
        val frame = reply {
            section(HostProtocol.CAST_V1) {
                writeInt(1)
                person("A", role = "r".repeat(HostProtocol.MAX_SECTION_BYTES))
            }
            section(HostProtocol.RECOMMENDATIONS_V1) { writeInt(1); suggestion("Saltwind", "https://site.example/s") }
        }
        val details = received(frame, dropped)
        assertEquals(emptyList(), details.cast)
        assertEquals(listOf("Saltwind"), details.recommendations.map { it.title })
        assertEquals(listOf("cast: a section past its bytes"), dropped)
    }

    @Test
    fun `a negative count costs the cast and keeps the page`() {
        val dropped = ArrayList<String>()
        val details = received(cast { writeInt(-1) }, dropped)
        assertEquals(page, details)
        assertEquals(listOf("cast: a section that could not be read"), dropped)
    }

    @Test
    fun `a field length lying inside the slice costs the cast, and what follows is still read`() {
        val frame = reply {
            section(HostProtocol.CAST_V1) {
                writeInt(2)
                person("A")
                writeInt(Int.MAX_VALUE) // a name said to run far past its slice
            }
            section(HostProtocol.RECOMMENDATIONS_V1) { writeInt(1); suggestion("Saltwind", "https://site.example/s") }
        }
        val details = received(frame)
        assertEquals(emptyList(), details.cast)
        assertEquals(listOf("Saltwind"), details.recommendations.map { it.title }, "the reader stepped past the slice")
    }

    @Test
    fun `a slice cut short costs the cast`() {
        assertEquals(emptyList(), castOf(cast { writeInt(3); person("A") }))
    }

    @Test
    fun `a section length lying past the frame fails the call`() {
        assertFailsWith<IOException> {
            received(reply { writeByte(HostProtocol.CAST_V1); writeInt(Int.MAX_VALUE) })
        }
        assertFailsWith<IOException> {
            received(reply { writeByte(HostProtocol.CAST_V1); writeInt(-1) })
        }
    }

    @Test
    fun `nothing the plugin sent reaches the log, not even a number`() {
        val dropped = ArrayList<String>()
        received(
            reply {
                section(HostProtocol.CAST_V1) {
                    writeInt(77_777)
                    person("SECRET-NAME‮", kind = 99, role = "SECRET-ROLE".repeat(10), voice = "​", image = "https://u:SECRET@img.example/a")
                    rawText(ByteArray(4096) { 'S'.code.toByte() }); writeByte(0); writeOptional(null); writeOptional(null); writeOptional(null)
                    repeat(30) { person("x".repeat(65)) }
                }
            },
            dropped,
        )
        assertTrue(dropped.isNotEmpty())
        dropped.forEach { line ->
            assertTrue("SECRET" !in line && "77" !in line && "99" !in line && "4096" !in line, "a plugin's value in the log: $line")
            assertTrue(line.none(Char::isDigit), "a number in the log: $line")
        }
        assertEquals(dropped.distinct(), dropped, "each reason once")
    }
}
