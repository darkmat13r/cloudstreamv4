package com.lagradost.cloudstream4.media.subtitle

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/**
 * A subtitle file is third-party input. These inputs were each chosen to break a specific guard,
 * and each guard was mutation-checked (`docs/tasks/kmp.md` `KMP-21`).
 *
 * ## Why the budget is absolute, and generous
 *
 * Nothing can interrupt a stuck regex or a quadratic loop from common code, so a regression shows
 * as a slow failure rather than an instant one. The inputs are sized so that linear code takes
 * milliseconds and quadratic code takes tens of seconds on the JVM. The gap is what makes the test
 * safe to run beside other builds, and on the slower Kotlin/Native debug binary.
 */
class SubtitleAdversarialTest {

    private val budget = 15.seconds

    /** Raises the guards that would otherwise hide the behaviour of the code underneath. */
    private val wide = SubtitleLimits(maxInputSize = 64 * 1024 * 1024, maxLineChars = 64 * 1024 * 1024)

    private val timing = "00:00:01,000 --> 00:00:02,000\n"

    private fun parseWithin(text: String, limits: SubtitleLimits = SubtitleLimits()): SubtitleParseResult {
        val (result, elapsed) = measureTimedValue { SubtitleParser.parse(text, SubtitleFormat.SRT, limits) }
        assertTrue(elapsed < budget, "took $elapsed")
        return result
    }

    private fun assertLinearAsCueText(line: String) {
        val result = parseWithin(timing + line, wide)
        assertEquals(1, result.cues.size)
    }

    @Test
    fun `repeated unclosed tag openers stay linear`() = assertLinearAsCueText("<a".repeat(1_000_000))

    @Test
    fun `repeated unclosed ssa openers stay linear`() = assertLinearAsCueText("{\\".repeat(1_000_000))

    @Test
    fun `repeated timestamp tag openers stay linear`() = assertLinearAsCueText("<1:".repeat(300_000))

    @Test
    fun `repeated ampersands stay linear`() = assertLinearAsCueText("&".repeat(1_000_000))

    @Test
    fun `tag openers with one late closer stay linear`() {
        val result = parseWithin(timing + "<a".repeat(1_000_000) + ">kept", wide)
        assertEquals(listOf("kept"), result.cues.single().text)
    }

    @Test
    fun `a whitespace run before an arrow stays linear`() {
        val result = parseWithin(" ".repeat(1_000_000) + "--> x", wide)
        assertTrue(result.cues.isEmpty())
    }

    @Test
    fun `a huge digit run in a timecode stays linear and is rejected`() {
        val result = parseWithin("1".repeat(1_000_000) + ":00 --> 00:01\nx", wide)
        assertTrue(result.cues.isEmpty())
        assertEquals(1, result.malformedCues)
    }

    @Test
    fun `a huge fraction stays linear and is truncated`() {
        val result = parseWithin("00:01." + "5".repeat(1_000_000) + " --> 00:02\nx", wide)
        assertEquals(1555, result.cues.single().startTimeMs)
    }

    @Test
    fun `many timing shaped lines with no text stay linear`() {
        val line = "00:00:01,000 --> 00:00:02,000\n"
        val result = parseWithin(line.repeat(100_000), wide)
        assertTrue(result.cues.isEmpty())
    }

    @Test
    fun `many near miss timing lines stay linear`() {
        val result = parseWithin("00:00:01,000 --> 00:00:0x\n".repeat(100_000), wide)
        assertEquals(100_000, result.malformedCues)
    }

    @Test
    fun `only line endings at the default size limit stay linear`() {
        val limits = SubtitleLimits()
        assertTrue(parseWithin("\r\n".repeat(limits.maxInputSize / 2), limits).cues.isEmpty())
        assertTrue(parseWithin("\r".repeat(limits.maxInputSize), limits).cues.isEmpty())
    }

    @Test
    fun `one enormous line is cut to the line limit`() {
        val limits = SubtitleLimits(maxLineChars = 100)
        val result = parseWithin(timing + "x".repeat(5_000_000), limits)
        assertEquals("x".repeat(100), result.cues.single().text.single())
        assertEquals(setOf(SubtitleTruncation.LINE_LENGTH), result.truncations)
    }

    @Test
    fun `a line limit cut inside a surrogate pair leaves no lone surrogate`() {
        // Long enough for the timing line, which a shorter limit would refuse to parse.
        val limits = SubtitleLimits(maxLineChars = 30)
        val kept = "a".repeat(29)
        val result = parseWithin(timing + kept + "\uD83D\uDE00", limits)
        assertEquals(kept, result.cues.single().text.single())
    }

    @Test
    fun `a timing line past the line limit is not parsed`() {
        val padded = "00:00:01,000 --> 00:00:02,000" + " ".repeat(200) + "\nx"
        val result = parseWithin(padded, SubtitleLimits(maxLineChars = 100))
        assertTrue(result.cues.isEmpty())
        assertEquals(setOf(SubtitleTruncation.LINE_LENGTH), result.truncations)
    }

    @Test
    fun `the cue count is capped and reported`() {
        val limits = SubtitleLimits(maxCues = 10)
        val result = parseWithin((timing + "x\n\n").repeat(1_000), limits)
        assertEquals(10, result.cues.size)
        assertEquals(setOf(SubtitleTruncation.CUE_COUNT), result.truncations)
    }

    @Test
    fun `exactly the cue limit is not reported as truncated`() {
        val result = parseWithin((timing + "x\n\n").repeat(10), SubtitleLimits(maxCues = 10))
        assertEquals(10, result.cues.size)
        assertTrue(result.truncations.isEmpty())
    }

    @Test
    fun `lines per cue are capped and the next cue still parses`() {
        val limits = SubtitleLimits(maxLinesPerCue = 3)
        val result = parseWithin(timing + "x\n".repeat(50) + "\n00:00:05,000 --> 00:00:06,000\ny", limits)
        assertEquals(listOf(listOf("x", "x", "x"), listOf("y")), result.cues.map { it.text })
        assertEquals(setOf(SubtitleTruncation.LINES_PER_CUE), result.truncations)
    }

    @Test
    fun `line break tags cannot get past the lines per cue cap`() {
        val limits = SubtitleLimits(maxLinesPerCue = 3)
        val result = parseWithin(timing + "x<br>".repeat(50), limits)
        assertEquals(3, result.cues.single().text.size)
        assertEquals(setOf(SubtitleTruncation.LINES_PER_CUE), result.truncations)
    }

    @Test
    fun `string input past the size limit is ignored and reported`() {
        val result = parseWithin((timing + "x\n\n").repeat(100), SubtitleLimits(maxInputSize = 40))
        assertEquals(1, result.cues.size)
        assertEquals(setOf(SubtitleTruncation.INPUT_SIZE), result.truncations)
    }

    @Test
    fun `byte input past the size limit is ignored and reported`() {
        val bytes = (timing + "x\n\n").repeat(100).encodeToByteArray()
        val result = SubtitleParser.parse(bytes, SubtitleFormat.SRT, SubtitleLimits(maxInputSize = 40))
        assertEquals(1, result.cues.size)
        assertEquals(setOf(SubtitleTruncation.INPUT_SIZE), result.truncations)
    }

    @Test
    fun `a timecode too long for a long is malformed not an exception`() {
        val huge = "99999999999999999999999:00:00,000"
        val result = parseWithin("$huge --> $huge\nx\n\n00:00:01,000 --> 00:00:02,000\nkept")
        assertEquals(listOf(listOf("kept")), result.cues.map { it.text })
        assertEquals(1, result.malformedCues)
    }

    @Test
    fun `a time past the maximum is malformed`() {
        val result = parseWithin("999999999:00:00,000 --> 999999999:00:01,000\nx")
        assertTrue(result.cues.isEmpty())
        assertEquals(1, result.malformedCues)
    }

    @Test
    fun `every accepted time keeps end arithmetic in range`() {
        val top = "1000:00:00,000"
        val cue = parseWithin("$top --> $top\nx").cues.single()
        assertEquals(SubtitleParser.MAX_TIMESTAMP_MS, cue.endTimeMs)
    }

    @Test
    fun `non ascii digits are not digits`() {
        // Char.isDigit() accepts these, and `- '0'` on them would produce a nonsense time.
        val arabicIndic = "٠٠:٠١"
        val result = parseWithin("$arabicIndic --> $arabicIndic\nx")
        assertTrue(result.cues.isEmpty())
    }

    @Test
    fun `degenerate inputs give nothing and do not throw`() {
        for (input in listOf(
            "", " ", "\n", "\r", "\uFEFF", "WEBVTT", "-->", "1", "1\n", "1\n-->", "-->\n-->",
            "00:00", "00:00 -->", "--> 00:00", ":", "::", ":::-->:::", ",-->.", "00:00 --> 00:01",
        )) {
            assertTrue(SubtitleParser.parse(input).cues.isEmpty(), "auto: [$input]")
            assertTrue(SubtitleParser.parse(input, SubtitleFormat.SRT).cues.isEmpty(), "srt: [$input]")
            assertTrue(SubtitleParser.parse(input, SubtitleFormat.VTT).cues.isEmpty(), "vtt: [$input]")
        }
    }

    @Test
    fun `degenerate byte inputs do not throw`() {
        val inputs = listOf(
            byteArrayOf(),
            byteArrayOf(0xEF.toByte()),
            byteArrayOf(0xEF.toByte(), 0xBB.toByte()),
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()),
            byteArrayOf(0xFF.toByte()),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41),
            byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0x00),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00),
            byteArrayOf(0xC0.toByte(), 0xAF.toByte(), 0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
        )
        for (input in inputs) {
            assertTrue(SubtitleParser.parse(input).cues.isEmpty(), input.contentToString())
        }
    }

    @Test
    fun `an invalid limit is a programmer error`() {
        assertFailsWith<IllegalArgumentException> { SubtitleLimits(maxCues = 0) }
        assertFailsWith<IllegalArgumentException> { SubtitleLimits(maxLineChars = -1) }
    }

    /** What every cue must satisfy, whatever the input. */
    private fun assertInvariants(result: SubtitleParseResult, limits: SubtitleLimits, context: String) {
        assertTrue(result.cues.size <= limits.maxCues, context)
        for (cue in result.cues) {
            assertTrue(cue.durationMs >= 0, context)
            assertTrue(cue.startTimeMs in 0..SubtitleParser.MAX_TIMESTAMP_MS, context)
            assertTrue(cue.endTimeMs in 0..SubtitleParser.MAX_TIMESTAMP_MS, context)
            assertTrue(cue.text.isNotEmpty(), context)
            assertTrue(cue.text.size <= limits.maxLinesPerCue, context)
            for (line in cue.text) {
                assertTrue(line.isNotEmpty(), context)
                assertTrue(line.length <= limits.maxLineChars, context)
                for (index in line.indices) {
                    val c = line[index]
                    assertTrue(c.code >= 0x20 && c.code !in 0x7F..0x9F, "control char in cue: $context")
                    if (c.isHighSurrogate()) {
                        assertTrue(index + 1 < line.length && line[index + 1].isLowSurrogate(), context)
                    }
                    if (c.isLowSurrogate()) {
                        assertTrue(index > 0 && line[index - 1].isHighSurrogate(), context)
                    }
                }
            }
        }
    }

    private val tokens = listOf(
        "\n", "\n\n", "\r\n", "\r", " ", "\t", "-->", " --> ", "00:00:01,000", "00:01.5", "99:99:99.999",
        "1", ":", ",", ".", "<", ">", "</", "<b>", "<br>", "<1:", "{\\", "}", "{\\an8}", "&", ";", "&amp;",
        "&#", "&#x", "&#x1F600;", "&#0;", "WEBVTT", "NOTE", "STYLE", "x", "\uFEFF", "\u0000", "\u001B",
        "\uD83D", "\uDE00", "\u202E", "\u2028", "\uFFFD",
    )

    @Test
    fun `random token soup never throws and always meets the invariants`() {
        val limits = SubtitleLimits(maxCues = 20, maxLineChars = 64, maxLinesPerCue = 4)
        val random = Random(20261102)
        repeat(4_000) { round ->
            val text = buildString {
                repeat(random.nextInt(60)) { append(tokens[random.nextInt(tokens.size)]) }
            }
            for (format in listOf(null, SubtitleFormat.SRT, SubtitleFormat.VTT)) {
                assertInvariants(SubtitleParser.parse(text, format, limits), limits, "round $round $format")
            }
        }
    }

    @Test
    fun `random bytes never throw and always meet the invariants`() {
        val limits = SubtitleLimits(maxCues = 20, maxLineChars = 64, maxLinesPerCue = 4)
        val random = Random(4242)
        val valid = (timing + "<i>Hello</i>\n\n").encodeToByteArray()
        repeat(4_000) { round ->
            // Half pure noise, half a valid file with bytes flipped, which reaches deeper.
            val bytes = if (round % 2 == 0) {
                random.nextBytes(random.nextInt(200))
            } else {
                valid.copyOf().also { copy ->
                    repeat(1 + random.nextInt(8)) { copy[random.nextInt(copy.size)] = random.nextInt().toByte() }
                }
            }
            for (format in listOf(null, SubtitleFormat.SRT, SubtitleFormat.VTT)) {
                assertInvariants(SubtitleParser.parse(bytes, format, limits), limits, "round $round $format")
            }
        }
    }
}
