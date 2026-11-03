package com.lagradost.cloudstream4.media.subtitle

import com.lagradost.cloudstream4.media.model.SubtitleCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real-world shapes, matched to what `:app`'s `CustomSubripParser` accepts. No backtick commas: `INF-04`. */
class SubtitleParserTest {

    private val srt = """
        1
        00:00:01,000 --> 00:00:02,500
        Hello

        2
        00:00:03,000 --> 00:00:04,000
        Two
        lines
    """.trimIndent()

    private fun cue(start: Long, duration: Long, vararg lines: String) =
        SubtitleCue(start, duration, lines.toList())

    private fun onlyCue(text: String, format: SubtitleFormat = SubtitleFormat.SRT): SubtitleCue =
        SubtitleParser.parse(text, format).cues.single()

    private fun startOf(timing: String): Long =
        onlyCue("1\n$timing --> 999:00:00,000\nx").startTimeMs

    @Test
    fun `plain srt parses into timed multi line cues`() {
        val result = SubtitleParser.parse(srt)
        assertEquals(SubtitleFormat.SRT, result.format)
        assertEquals(listOf(cue(1000, 1500, "Hello"), cue(3000, 1000, "Two", "lines")), result.cues)
        assertEquals(0, result.malformedCues)
        assertTrue(result.truncations.isEmpty())
    }

    @Test
    fun `crlf and bare cr line endings parse the same as lf`() {
        val expected = SubtitleParser.parse(srt).cues
        assertEquals(expected, SubtitleParser.parse(srt.replace("\n", "\r\n")).cues)
        assertEquals(expected, SubtitleParser.parse(srt.replace("\n", "\r")).cues)
    }

    @Test
    fun `utf8 bom is removed`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + srt.encodeToByteArray()
        assertEquals(SubtitleParser.parse(srt).cues, SubtitleParser.parse(bytes).cues)
    }

    private fun utf16(text: String, bigEndian: Boolean): ByteArray {
        val bom = if (bigEndian) byteArrayOf(0xFE.toByte(), 0xFF.toByte()) else byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val body = ByteArray(text.length * 2)
        text.forEachIndexed { i, c ->
            val high = (c.code ushr 8).toByte()
            val low = c.code.toByte()
            body[i * 2] = if (bigEndian) high else low
            body[i * 2 + 1] = if (bigEndian) low else high
        }
        return bom + body
    }

    @Test
    fun `utf16 with a bom decodes in both byte orders`() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\nمرحبا 日本語"
        val expected = listOf(cue(1000, 1000, "مرحبا 日本語"))
        assertEquals(expected, SubtitleParser.parse(utf16(text, bigEndian = false)).cues)
        assertEquals(expected, SubtitleParser.parse(utf16(text, bigEndian = true)).cues)
    }

    @Test
    fun `a bom in an already decoded string is removed`() {
        assertEquals(1000, onlyCue("\uFEFF00:00:01,000 --> 00:00:02,000\nx").startTimeMs)
    }

    @Test
    fun `invisible characters before the first cue are skipped`() {
        assertEquals(1000, onlyCue("\u200B\u0001  \n1\n00:00:01,000 --> 00:00:02,000\nx").startTimeMs)
    }

    @Test
    fun `hours are optional as the fork exists to allow`() {
        assertEquals(61_500, startOf("01:01.500"))
    }

    @Test
    fun `dot and comma both separate the fraction`() {
        assertEquals(1_250, startOf("00:00:01.250"))
        assertEquals(1_250, startOf("00:00:01,250"))
    }

    @Test
    fun `fraction digits scale and then truncate`() {
        assertEquals(1_000, startOf("00:00:01"))
        assertEquals(1_500, startOf("00:00:01.5"))
        assertEquals(1_250, startOf("00:00:01.25"))
        assertEquals(1_123, startOf("00:00:01.123"))
        assertEquals(1_123, startOf("00:00:01.123999"))
    }

    @Test
    fun `fields may exceed their clock range and many hour digits are fine`() {
        assertEquals(90 * 60_000L, startOf("00:90:00,000"))
        assertEquals(75_000, startOf("00:00:75,000"))
        assertEquals(100L * 3_600_000, startOf("100:00:00,000"))
    }

    @Test
    fun `whitespace around the arrow is optional`() {
        assertEquals(1000, onlyCue("  00:00:01,000-->00:00:02,000  \nx").startTimeMs)
        assertEquals(1000, onlyCue("00:00:01,000 \t -->\t00:00:02,000\nx").startTimeMs)
    }

    @Test
    fun `srt coordinates after the end time are ignored`() {
        assertEquals(1000, onlyCue("00:00:01,000 --> 00:00:02,000 X1:40 X2:600 Y1:20 Y2:50\nx").durationMs)
    }

    @Test
    fun `missing and non integer index lines do not lose the cue`() {
        val cues = SubtitleParser.parse(
            "00:00:01,000 --> 00:00:02,000\nno index\n\n" +
                "1 \n00:00:03,000 --> 00:00:04,000\ntrailing space\n\n" +
                "abc\n00:00:05,000 --> 00:00:06,000\nwords\n\n" +
                "99999999999999\n00:00:07,000 --> 00:00:08,000\ntoo big for Int",
            SubtitleFormat.SRT,
        ).cues
        assertEquals(listOf(1000L, 3000L, 5000L, 7000L), cues.map { it.startTimeMs })
    }

    @Test
    fun `a missing blank line does not merge cues or show the next index`() {
        val cues = SubtitleParser.parse(
            "1\n00:00:01,000 --> 00:00:02,000\nFirst\n2\n00:00:03,000 --> 00:00:04,000\nSecond",
            SubtitleFormat.SRT,
        ).cues
        assertEquals(listOf(cue(1000, 1000, "First"), cue(3000, 1000, "Second")), cues)
    }

    @Test
    fun `a digits line is kept as text when no timing follows it`() {
        assertEquals(listOf("In", "1984"), onlyCue("00:00:01,000 --> 00:00:02,000\nIn\n1984\n").text)
    }

    @Test
    fun `whitespace only lines end a cue`() {
        val cues = SubtitleParser.parse(
            "1\n00:00:01,000 --> 00:00:02,000\nFirst\n   \n2\n00:00:03,000 --> 00:00:04,000\nSecond",
            SubtitleFormat.SRT,
        ).cues
        assertEquals(listOf(listOf("First"), listOf("Second")), cues.map { it.text })
    }

    @Test
    fun `malformed timings are counted and later cues survive`() {
        val result = SubtitleParser.parse(
            "1\n00:00:xx,000 --> 00:00:02,000\nlost\n\n" +
                "2\n00:00:01,000 -> 00:00:02,000\nno arrow so not counted\n\n" +
                "3\n1 --> 2\nsingle fields\n\n" +
                "4\n00:00:01:000 --> 00:00:02:000\nfour fields\n\n" +
                "5\n00:00:01, --> 00:00:02,\nempty fraction\n\n" +
                "6\n00:00:01,000 --> 00:00:02,000x\njunk glued to the end\n\n" +
                "7\n00:00:01,000 --> 00:00:02,000 --> 00:00:03,000\ntwo arrows\n\n" +
                "8\n00:00:09,000 --> 00:00:10,000\nkept",
            SubtitleFormat.SRT,
        )
        assertEquals(listOf(cue(9000, 1000, "kept")), result.cues)
        assertEquals(6, result.malformedCues)
    }

    @Test
    fun `an end before its start skips the cue and keeps its text out of the next`() {
        val result = SubtitleParser.parse(
            "00:00:05,000 --> 00:00:04,000\nbackwards\n\n00:00:06,000 --> 00:00:07,000\nfine",
            SubtitleFormat.SRT,
        )
        assertEquals(listOf(cue(6000, 1000, "fine")), result.cues)
        assertEquals(1, result.malformedCues)
    }

    @Test
    fun `a zero length cue is kept as the legacy parser keeps it`() {
        assertEquals(0, onlyCue("00:00:05,000 --> 00:00:05,000\nblink").durationMs)
    }

    @Test
    fun `a cue with no visible text is dropped`() {
        val text = "00:00:01,000 --> 00:00:02,000\n\n00:00:03,000 --> 00:00:04,000\n{\\an8}\n\n00:00:05,000 --> 00:00:06,000\n<i></i>"
        assertTrue(SubtitleParser.parse(text, SubtitleFormat.SRT).cues.isEmpty())
    }

    @Test
    fun `file order is kept`() {
        val cues = SubtitleParser.parse(
            "00:00:09,000 --> 00:00:10,000\nlate\n\n00:00:01,000 --> 00:00:02,000\nearly",
            SubtitleFormat.SRT,
        ).cues
        assertEquals(listOf("late", "early"), cues.map { it.text.single() })
    }

    @Test
    fun `webvtt header settings notes and identifiers are not shown`() {
        val result = SubtitleParser.parse(
            """
            WEBVTT - Some title
            Kind: captions
            X-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:900000

            NOTE a comment

            NOTE
            multi line
            00:00:50.000 --> 00:00:51.000
            comment with an arrow

            STYLE
            ::cue { color: red }
            00:00:60.000 --> 00:00:61.000

            REGION
            id:fred

            intro
            00:01.000 --> 00:02.000 line:90% position:50% align:start
            <v Bob>Hi <c.yellow>there</c></v>

            00:00:03.000 --> 00:00:04.000
            <00:00:03.500>Karaoke <b>text</b>
            """.trimIndent(),
        )
        assertEquals(SubtitleFormat.VTT, result.format)
        assertEquals(listOf(cue(1000, 1000, "Hi there"), cue(3000, 1000, "Karaoke text")), result.cues)
        assertEquals(0, result.malformedCues)
    }

    @Test
    fun `a note block is ordinary text skipping in srt`() {
        val text = "NOTE\n00:00:01,000 --> 00:00:02,000\nreal cue"
        assertEquals(1, SubtitleParser.parse(text, SubtitleFormat.SRT).cues.size)
        assertEquals(0, SubtitleParser.parse(text, SubtitleFormat.VTT).cues.size)
    }

    @Test
    fun `vtt with a comma and a srt tag still parses`() {
        val vtt = "WEBVTT\n\n00:00:01,000 --> 00:00:02,000\n{\\an8}Top"
        assertEquals(listOf(cue(1000, 1000, "Top")), SubtitleParser.parse(vtt).cues)
    }

    @Test
    fun `detect finds webvtt behind invisible characters in any case`() {
        assertEquals(SubtitleFormat.VTT, SubtitleParser.detect("WEBVTT"))
        assertEquals(SubtitleFormat.VTT, SubtitleParser.detect("\uFEFF\u200B  webvtt\n"))
        assertEquals(SubtitleFormat.VTT, SubtitleParser.detect("\uFFFDWEBVTT"))
    }

    @Test
    fun `detect finds srt not starting at index one`() {
        assertEquals(SubtitleFormat.SRT, SubtitleParser.detect("0\n00:00:01,000 --> 00:00:02,000\nx"))
        assertEquals(SubtitleFormat.SRT, SubtitleParser.detect("00:00:01,000 --> 00:00:02,000\nx"))
    }

    @Test
    fun `detect declines formats this module does not parse`() {
        assertNull(SubtitleParser.detect("[Script Info]\nTitle: x"))
        assertNull(SubtitleParser.detect("Title: x\n00:00:01,000 --> 00:00:02,000"))
        assertNull(SubtitleParser.detect("<?xml version=\"1.0\"?><tt><!-- x --></tt>"))
        assertNull(SubtitleParser.detect("<html>not found</html>"))
        assertNull(SubtitleParser.detect(""))
    }

    @Test
    fun `an unrecognised format parses to nothing and says so`() {
        val result = SubtitleParser.parse("[Script Info]\nDialogue: 0,0:00:01.00,0:00:02.00,x")
        assertNull(result.format)
        assertTrue(result.cues.isEmpty())
    }

    @Test
    fun `an explicit format overrides detection`() {
        val text = "Title: 00:00:01,000\n\n00:00:01,000 --> 00:00:02,000\nx"
        assertEquals(1, SubtitleParser.parse(text, SubtitleFormat.SRT).cues.size)
    }
}
