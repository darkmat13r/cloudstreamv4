package com.lagradost.cloudstream4.media.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StateFileTest {
    private val good = listOf(
        "csdl 1", "total 300", "etag \"abc\"", "modified Wed, 21 Oct 2015 07:28:00 GMT", "phase download",
        "part 0 0 100 100", "part 1 100 250 0", "part 2 250 300 7",
    )

    private fun text(lines: List<String>) = lines.joinToString("\n", postfix = "\n")

    private fun decode(lines: List<String>) = StateFile.decode(bytesOf(text(lines)))

    private fun withLine(index: Int, replacement: String?) =
        good.toMutableList().also { if (replacement == null) it.removeAt(index) else it[index] = replacement }

    private val state = PersistedState(
        total = 300, etag = "\"abc\"", modified = "Wed, 21 Oct 2015 07:28:00 GMT", phase = PersistedPhase.DOWNLOAD,
        parts = listOf(PersistedPart(0, 0, 100, 100), PersistedPart(1, 100, 250, 0), PersistedPart(2, 250, 300, 7)),
    )

    @Test
    fun `a good file decodes to the state it encodes`() {
        assertEquals(state, decode(good))
        assertEquals(state, StateFile.decode(StateFile.encode(state)))
        assertEquals(text(good), StateFile.encode(state).decodeToString())
    }

    @Test
    fun `empty validators round trip and use the short form`() {
        val bare = state.copy(etag = "", modified = "", phase = PersistedPhase.JOINING)
        val encoded = StateFile.encode(bare).decodeToString()
        assertTrue(encoded.contains("\netag\nmodified\nphase joining\n"))
        assertEquals(bare, StateFile.decode(StateFile.encode(bare)))
    }

    @Test
    fun `an empty file is a total of zero and no parts`() {
        val empty = PersistedState(0, "", "", PersistedPhase.DOWNLOAD, emptyList())
        assertEquals(empty, StateFile.decode(StateFile.encode(empty)))
        assertNull(decode(listOf("csdl 1", "total 0", "etag", "modified", "phase download", "part 0 0 0 0")))
        assertNull(decode(listOf("csdl 1", "total 5", "etag", "modified", "phase download")))
    }

    @Test
    fun `recordedDone is carried but never validated against the range`() {
        val lying = decode(withLine(5, "part 0 0 100 999999999999"))
        assertNotNull(lying)
        assertEquals(999_999_999_999L, lying.parts[0].recordedDone)
    }

    @Test
    fun `every header malformation is rejected`() {
        val bad = listOf(
            withLine(0, "csdl 2"), withLine(0, "csdl 1 "), withLine(0, "CSDL 1"), withLine(0, "csdl  1"), withLine(0, null),
            withLine(1, "total -1"), withLine(1, "total +300"), withLine(1, "total 0300"), withLine(1, "total 300 "),
            withLine(1, "total "), withLine(1, "total"), withLine(1, "total 3.0"), withLine(1, "total 0x10"),
            withLine(1, "total 99999999999999999999"), withLine(1, "total 9223372036854775808"),
            withLine(1, null), withLine(2, "etag "), withLine(2, "etag  x"), withLine(2, "etag x "), withLine(2, "etagx"),
            withLine(2, "etag " + "a".repeat(257)), withLine(3, "modified " + "a".repeat(65)), 
            withLine(2, "etag a${Char(0)}b"), withLine(2, "etag a\tb"), withLine(2, "etag caf${Char(0xE9)}"),
            withLine(2, "etag a\rb"), withLine(4, "phase paused"), withLine(4, "phase Download"), withLine(4, "phase  download"),
            withLine(4, "phase"), withLine(4, null),
        )
        bad.forEachIndexed { i, lines -> assertNull(decode(lines), "case $i: ${lines.joinToString("|")}") }
    }

    @Test
    fun `the longest permitted validators are accepted`() {
        val edge = withLine(2, "etag " + "a".repeat(256)).also { it[3] = "modified " + "b".repeat(64) }
        assertNotNull(decode(edge))
    }

    @Test
    fun `headers out of order or repeated are rejected`() {
        assertNull(decode(listOf(good[0], good[2], good[1]) + good.drop(3)))
        assertNull(decode(good.take(5) + "total 300" + good.drop(5)))
        assertNull(decode(good + "phase joining"))
        assertNull(decode(good + "junk"))
        assertNull(decode(good + ""))
        assertNull(decode(listOf("") + good))
        assertNull(decode(listOf("csdl 1")))
        assertNull(decode(emptyList()))
    }

    @Test
    fun `line ending and framing violations are rejected`() {
        assertNull(StateFile.decode(bytesOf(good.joinToString("\n"))))
        assertNull(StateFile.decode(bytesOf(good.joinToString("\r\n", postfix = "\r\n"))))
        assertNull(StateFile.decode(bytesOf(text(good) + "\n")))
        assertNull(StateFile.decode(bytesOf("")))
        assertNull(StateFile.decode(bytesOf("\n")))
        assertNull(StateFile.decode(bytesOf(text(good) + "x")))
        assertNull(StateFile.decode(bytesOf(text(good).replace("\n", "\n\u0000"))))
    }

    @Test
    fun `invalid utf8 is rejected`() {
        val valid = StateFile.encode(state)
        val broken = valid.copyOf()
        broken[2] = 0xC0.toByte()
        assertNull(StateFile.decode(broken))
        assertNull(StateFile.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x0A)))
    }

    @Test
    fun `every part malformation is rejected`() {
        val bad = listOf(
            withLine(5, "part 0 0 100"), withLine(5, "part 0 0 100 100 5"), withLine(5, "part 0 0 100 100 "), withLine(5, "part  0 0 100 100"),
            withLine(5, "Part 0 0 100 100"), withLine(5, "part x 0 100 100"), withLine(5, "part -1 0 100 100"),
            withLine(5, "part 0 -0 100 100"), withLine(5, "part 0 0 100 -5"), withLine(5, "part 00 0 100 100"),
            withLine(5, "part 0 0 100 0100"), withLine(5, "part 0 0 100 1e2"), withLine(5, "part 0 0 100 99999999999999999999"),
            withLine(5, "part 0 0 100 9223372036854775808"), withLine(5, "part 0 0 100 0x64"), withLine(5, "part 0 0 100\t100"),
            withLine(5, "part 128 0 100 0"), withLine(5, "part 4294967296 0 100 0"), withLine(5, "part 2147483648 0 100 0"),
            withLine(5, "part 1 0 100 0"), withLine(5, "part 0 0 0 0"), withLine(5, "part 0 100 100 0"), withLine(5, "part 0 100 0 0"),
        )
        bad.forEachIndexed { i, lines -> assertNull(decode(lines), "case $i: ${lines[5]}") }
    }

    @Test
    fun `overlapping ranges are rejected`() {
        assertNull(decode(withLine(6, "part 1 99 250 0")))
        assertNull(decode(withLine(6, "part 1 0 250 0")))
        assertNull(decode(listOf("csdl 1", "total 300", "etag", "modified", "phase download", "part 0 0 200 0", "part 1 100 300 0")))
    }

    @Test
    fun `gaps are rejected at the start in the middle and at the end`() {
        assertNull(decode(withLine(5, "part 0 1 100 0")))
        assertNull(decode(withLine(6, "part 1 101 250 0")))
        assertNull(decode(withLine(7, "part 2 250 299 7")))
        assertNull(decode(withLine(7, "part 2 250 301 7")))
        assertNull(decode(withLine(7, null)))
    }

    @Test
    fun `ranges out of order and duplicate ids are rejected`() {
        assertNull(decode(listOf(good[0], good[1], good[2], good[3], good[4], good[6], good[5], good[7])))
        assertNull(decode(withLine(6, "part 0 100 250 0")))
        assertNull(decode(withLine(7, "part 1 250 300 7")))
    }

    @Test
    fun `ids need not be consecutive or ordered only distinct and in range`() {
        val sparse = decode(listOf("csdl 1", "total 10", "etag", "modified", "phase download", "part 5 0 4 0", "part 2 4 10 0"))
        assertNotNull(sparse)
        assertEquals(listOf(5, 2), sparse.parts.map { it.id })
        assertNotNull(decode(listOf("csdl 1", "total 10", "etag", "modified", "phase download", "part 127 0 10 0")))
    }

    @Test
    fun `a part count over the cap is rejected and the cap itself is accepted`() {
        fun many(count: Int, total: Long = count.toLong()) = listOf("csdl 1", "total $total", "etag", "modified", "phase download") +
            (0 until count).map { "part ${it % StateFile.MAX_PARTS} $it ${it + 1} 0" }
        assertNotNull(decode(many(StateFile.MAX_PARTS)))
        assertNull(decode(many(StateFile.MAX_PARTS + 1)))
        assertNull(decode(many(5_000)))
    }

    @Test
    fun `an oversized file is rejected before it is read as text`() {
        val padded = bytesOf(text(good)) + ByteArray(StateFile.MAX_BYTES) { ' '.code.toByte() }
        assertNull(StateFile.decode(padded))
        val tooLongEtag = bytesOf(text(withLine(2, "etag " + "a".repeat(StateFile.MAX_BYTES))))
        assertNull(StateFile.decode(tooLongEtag))
    }

    @Test
    fun `huge numbers do not overflow the contiguity check`() {
        val max = Long.MAX_VALUE
        val ok = decode(listOf("csdl 1", "total $max", "etag", "modified", "phase download", "part 0 0 $max 0"))
        assertNotNull(ok)
        val split = decode(listOf("csdl 1", "total $max", "etag", "modified", "phase download", "part 0 0 4611686018427387904 0", "part 1 4611686018427387904 $max 0"))
        assertNotNull(split)
        assertNull(decode(listOf("csdl 1", "total $max", "etag", "modified", "phase download", "part 0 0 $max 0", "part 1 $max ${max} 0")))
        assertNull(decode(listOf("csdl 1", "total 10", "etag", "modified", "phase download", "part 0 0 $max 0")))
    }

    @Test
    fun `encoding an invalid state is a bug and throws`() {
        assertFailsWith<IllegalArgumentException> { StateFile.encode(state.copy(total = 301)) }
        assertFailsWith<IllegalArgumentException> { StateFile.encode(state.copy(total = -1, parts = emptyList())) }
        assertFailsWith<IllegalArgumentException> { StateFile.encode(state.copy(etag = "a\nb")) }
        assertFailsWith<IllegalArgumentException> { StateFile.encode(state.copy(parts = state.parts.reversed())) }
        assertFailsWith<IllegalArgumentException> { StateFile.encode(state.copy(parts = state.parts + state.parts.last())) }
    }

    @Test
    fun `storable validators drop what cannot be stored`() {
        assertEquals("\"abc\"", storableValidator("  \"abc\" ", StateFile.MAX_ETAG))
        assertEquals("", storableValidator(null, StateFile.MAX_ETAG))
        assertEquals("", storableValidator("a\nb", StateFile.MAX_ETAG))
        assertEquals("", storableValidator("x".repeat(257), StateFile.MAX_ETAG))
        assertEquals("", storableValidator("caf${Char(0xE9)}", StateFile.MAX_ETAG))
        assertEquals("x".repeat(256), storableValidator("x".repeat(256), StateFile.MAX_ETAG))
    }

    @Test
    fun `decode never throws on arbitrary bytes`() {
        var seed = 12345L
        repeat(500) {
            val size = (it * 7) % 300
            val bytes = ByteArray(size) { seed = seed * 6364136223846793005L + 1442695040888963407L; (seed ushr 33).toByte() }
            StateFile.decode(bytes)
        }
        // And every prefix and single-byte mutation of a good file.
        val valid = StateFile.encode(state)
        for (cut in valid.indices) StateFile.decode(valid.copyOf(cut))
        for (i in valid.indices) StateFile.decode(valid.copyOf().also { b -> b[i] = (b[i] + 1).toByte() })
    }
}
