package com.lagradost.cloudstream4.media.subtitle

import com.lagradost.cloudstream4.media.model.CueAnchor
import com.lagradost.cloudstream4.media.model.CuePlacement
import com.lagradost.cloudstream4.media.model.CueTextAlign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The two real shapes (`KMP-25`): what YouTube's timedtext sends, and ARD's EBU-TT-D. Words replaced. */
class TtmlParserTest {

    private val youtube = """
        <?xml version="1.0" encoding="utf-8" ?>
        <tt xml:lang="nl" xmlns="http://www.w3.org/ns/ttml" xmlns:tts="http://www.w3.org/ns/ttml#styling" ttp:profile="http://www.w3.org/TR/profile/sdp-us" >
        <head>
        <styling>
        <style xml:id="s1" tts:textAlign="center" tts:extent="90% 90%" tts:origin="5% 5%" tts:displayAlign="after"/>
        <style xml:id="s2" tts:fontSize=".72c" tts:backgroundColor="black" tts:color="white"/>
        </styling>
        <layout>
        <region xml:id="r1" style="s1"/>
        </layout>
        </head>
        <body region="r1">
        <div>
        <p begin="00:00:02.705" end="00:00:05.360" style="s2">one two</p>
        <p begin="00:00:04.407" end="00:00:07.204" style="s2"> three four</p>
        </div>
        </body>
        </tt>
    """.trimIndent()

    private val ard = """
        <?xml version="1.0" encoding="UTF-8"?><!-- Profile: EBU-TT-D-Basic-DE --><tt:tt xmlns:tt="http://www.w3.org/ns/ttml" xmlns:tts="http://www.w3.org/ns/ttml#styling" xmlns:ebuttdt="urn:ebu:tt:datatypes" ttp:timeBase="media" ttp:cellResolution="50 30" xml:lang="de">
        <tt:head><tt:metadata><ebuttm:documentMetadata><ebuttm:documentEbuttVersion>v1.0</ebuttm:documentEbuttVersion></ebuttm:documentMetadata></tt:metadata>
        <tt:styling><tt:style xml:id="S1" tts:fontSize="160%"/><tt:style xml:id="S2" tts:textAlign="center"/><tt:style xml:id="S3" tts:color="#ffffff"/></tt:styling>
        <tt:layout><tt:region xml:id="R1" tts:origin="10% 10%" tts:extent="80% 80%" tts:displayAlign="after"/></tt:layout></tt:head>
        <tt:body><tt:div style="S1">
        <tt:p xml:id="C1" region="R1" style="S2" begin="10:00:02.120" end="10:00:03.600"><tt:span style="S3">(Musik)</tt:span></tt:p>
        <tt:p xml:id="C2" region="R1" style="S2" begin="10:00:03.680" end="10:00:07.040">
        <tt:span style="S3">first line</tt:span><tt:br/><tt:span style="S3">second &amp; line</tt:span>
        </tt:p>
        </tt:div></tt:body></tt:tt>
    """.trimIndent()

    private fun ttml(body: String, root: String = "") =
        SubtitleParser.parse("""<tt xmlns="http://www.w3.org/ns/ttml"$root><body><div>$body</div></body></tt>""", SubtitleFormat.TTML)

    @Test
    fun `detection finds both real shapes and nothing else`() {
        assertEquals(SubtitleFormat.TTML, SubtitleParser.detect(youtube))
        assertEquals(SubtitleFormat.TTML, SubtitleParser.detect(ard))
        assertEquals(SubtitleFormat.TTML, SubtitleParser.detect("<tt/>"))
        assertEquals(null, SubtitleParser.detect("<ttx/>"))
        assertEquals(null, SubtitleParser.detect("<html><p begin=\"1s\"></p></html>"))
    }

    @Test
    fun `the youtube shape gives timed text at the region's foot overlaps kept`() {
        val result = SubtitleParser.parse(youtube)
        assertEquals(SubtitleFormat.TTML, result.format)
        assertEquals(listOf(2705L, 4407L), result.cues.map { it.startTimeMs })
        assertEquals(5360L - 2705L, result.cues[0].durationMs)
        assertEquals(listOf(listOf("one two"), listOf("three four")), result.cues.map { it.text })
        val placement = result.cues[0].placement
        assertEquals(95f, placement.verticalPercent)
        assertEquals(CueAnchor.END, placement.verticalAnchor)
        assertEquals(50f, placement.horizontalPercent)
        assertEquals(CueAnchor.MIDDLE, placement.horizontalAnchor)
        assertEquals(CueTextAlign.CENTER, placement.textAlign)
        assertEquals(90f, placement.widthPercent)
        assertEquals(0, result.malformedCues)
    }

    @Test
    fun `the ebu programme start comes off br breaks lines entities decode and whitespace collapses`() {
        val result = SubtitleParser.parse(ard)
        // 2120 is what ARD's VTT twin of this file says for its first cue. Measured on the real pair.
        assertEquals(listOf(2120L, 3680L), result.cues.map { it.startTimeMs })
        assertEquals(1480L, result.cues[0].durationMs)
        assertEquals(listOf("first line", "second & line"), result.cues[1].text)
        assertEquals(90f, result.cues[0].placement.verticalPercent)
        assertEquals(50f, result.cues[0].placement.horizontalPercent)
    }

    @Test
    fun `only an ebu file loses its hours`() {
        val plain = ttml("""<p begin="10:00:00.000" end="10:00:01.000">x</p>""")
        assertEquals(36_000_000L, plain.cues.single().startTimeMs)
        val ebu = SubtitleParser.parse(
            """<tt xmlns:e="urn:ebu:tt:metadata"><body><p begin="10:00:00.000" end="10:00:01.000">x</p><p begin="11:30:00.000" end="11:30:01.000">y</p></body></tt>""",
        )
        assertEquals(listOf(0L, 5_400_000L), ebu.cues.map { it.startTimeMs })
    }

    @Test
    fun `an ebu file whose first cue is inside the first hour keeps its times`() {
        val ebu = SubtitleParser.parse(
            """<tt xmlns:e="urn:ebu:tt:metadata"><body><p begin="00:10:00.000" end="00:10:01.000">x</p></body></tt>""",
        )
        assertEquals(600_000L, ebu.cues.single().startTimeMs)
    }

    @Test
    fun `every time expression`() {
        fun start(begin: String, root: String = "") = ttml("""<p begin="$begin" dur="1s">x</p>""", root).cues.single().startTimeMs
        assertEquals(3_723_000L, start("01:02:03"))
        assertEquals(3_723_450L, start("01:02:03.45"))
        assertEquals(3_723_450L, start("01:02:03.4509"))
        assertEquals(3_723_500L, start("01:02:03:15"))
        assertEquals(3_723_600L, start("01:02:03:15", """ ttp:frameRate="25""""))
        assertEquals(12_500L, start("12.5s"))
        assertEquals(500L, start("500ms"))
        assertEquals(90_000L, start("1.5m"))
        assertEquals(3_600_000L, start("1h"))
        assertEquals(1_000L, start("30f"))
        assertEquals(500L, start("5000t", """ ttp:tickRate="10000""""))
    }

    @Test
    fun `a begin on the containers adds to what is inside`() {
        val result = SubtitleParser.parse(
            """<tt><body begin="10s"><div begin="5s"><p begin="1s" end="2s">x</p></div><p begin="1s" end="2s">y</p></body></tt>""",
        )
        assertEquals(listOf(16_000L, 11_000L), result.cues.map { it.startTimeMs })
    }

    @Test
    fun `dur is honoured and end wins over it`() {
        assertEquals(2_000L, ttml("""<p begin="1s" dur="2s">x</p>""").cues.single().durationMs)
        assertEquals(4_000L, ttml("""<p begin="1s" end="5s" dur="2s">x</p>""").cues.single().durationMs)
    }

    @Test
    fun `an unusable time is counted and later paragraphs survive`() {
        val result = ttml(
            """<p begin="soon" end="later">a</p><p end="1s">b</p><p begin="1s">c</p><p begin="5s" end="1s">d</p>""" +
                """<p begin="1:2" end="2s">e</p><p begin="99999999999:00:00" end="2s">f</p><p begin="1s" end="2s">kept</p>""",
        )
        assertEquals(listOf(listOf("kept")), result.cues.map { it.text })
        assertEquals(6, result.malformedCues)
    }

    @Test
    fun `placement from a style nested in the region and from attributes on the paragraph`() {
        val nested = SubtitleParser.parse(
            """<tt><head><layout><region xml:id="r"><style tts:origin="0% 0%"/><style tts:extent="100% 20%" tts:displayAlign="before"/></region></layout></head>""" +
                """<body><p region="r" begin="1s" end="2s">x</p></body></tt>""",
        ).cues.single().placement
        assertEquals(0f, nested.verticalPercent)
        assertEquals(CueAnchor.START, nested.verticalAnchor)
        val own = ttml("""<p tts:textAlign="right" begin="1s" end="2s">x</p>""").cues.single().placement
        assertEquals(100f, own.horizontalPercent)
        assertEquals(CueAnchor.END, own.horizontalAnchor)
        assertEquals(CueTextAlign.RIGHT, own.textAlign)
    }

    @Test
    fun `no placement information stays at the bottom centre and pixels are not honoured`() {
        assertEquals(CuePlacement.Default, ttml("""<p begin="1s" end="2s">x</p>""").cues.single().placement)
        val pixels = SubtitleParser.parse(
            """<tt><head><layout><region xml:id="r" tts:origin="640px 360px"/></layout></head><body><p region="r" begin="1s" end="2s">x</p></body></tt>""",
        ).cues.single().placement
        assertEquals(CuePlacement.Default, pixels)
    }

    @Test
    fun `text is plain whatever it tries`() {
        val cue = ttml(
            """<p begin="1s" end="2s">&lt;a href="x"&gt;link&lt;/a&gt; <span>&#x202E;kept</span> {\an8}<metadata>never shown</metadata></p>""",
        ).cues.single()
        assertEquals(1, cue.text.size)
        assertTrue("never shown" !in cue.text.single())
        assertTrue("{" !in cue.text.single())
        assertTrue("kept" in cue.text.single())
    }

    @Test
    fun `a doctype expands nothing`() {
        val cue = SubtitleParser.parse(
            """<?xml version="1.0"?><!DOCTYPE tt [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;">]><tt><body><p begin="1s" end="2s">&b;</p></body></tt>""",
            SubtitleFormat.TTML,
        ).cues.single()
        assertEquals(listOf("&b;"), cue.text)
    }

    @Test
    fun `cdata is text and comments are not`() {
        val cue = ttml("""<p begin="1s" end="2s">a<!-- b --><![CDATA[ c ]]></p>""").cues.single()
        assertEquals(listOf("a c"), cue.text)
    }

    @Test
    fun `malformed documents give what was complete and never throw`() {
        for (document in listOf(
            "<tt>", "<tt><p", "<tt><p begin=\"1s", "<tt><p begin=\"1s\" end=\"2s\"", "<", "<>", "</>", "<<<<", "<tt><p =\"\" =>x</p>",
            "<tt><!--", "<tt><![CDATA[", "<tt><?x", "<tt><!x", "<tt></p></div></body></tt>", "<tt><br/><span>",
            "<tt><p begin=1s end=2s>x</p></tt>", "<tt><style xml:id='s' style='s s s'/><p style='s' begin='1s' end='2s'>y</p>",
        )) {
            SubtitleParser.parse(document, SubtitleFormat.TTML)
        }
        val unclosed = SubtitleParser.parse("""<tt><body><p begin="1s" end="2s">still here""", SubtitleFormat.TTML)
        assertEquals(listOf(listOf("still here")), unclosed.cues.map { it.text })
    }

    @Test
    fun `the bounds hold`() {
        val many = buildString {
            append("<tt><body>")
            repeat(200) { append("""<p begin="1s" end="2s">x</p>""") }
            append("</body></tt>")
        }
        val capped = SubtitleParser.parse(many, SubtitleFormat.TTML, SubtitleLimits(maxCues = 10))
        assertEquals(10, capped.cues.size)
        assertTrue(SubtitleTruncation.CUE_COUNT in capped.truncations)

        val long = SubtitleParser.parse(
            """<tt><p begin="1s" end="2s">${"x".repeat(5000)}</p></tt>""",
            SubtitleFormat.TTML,
            SubtitleLimits(maxLineChars = 100),
        )
        assertEquals(100, long.cues.single().text.single().length)
        assertTrue(SubtitleTruncation.LINE_LENGTH in long.truncations)

        val breaks = SubtitleParser.parse(
            """<tt><p begin="1s" end="2s">${"a<br/>".repeat(50)}</p></tt>""",
            SubtitleFormat.TTML,
            SubtitleLimits(maxLinesPerCue = 4),
        )
        assertEquals(4, breaks.cues.single().text.size)
        assertTrue(SubtitleTruncation.LINES_PER_CUE in breaks.truncations)

        val input = SubtitleParser.parse(
            """<tt><p begin="1s" end="2s">kept</p><p begin="3s" end="4s">gone</p></tt>""",
            SubtitleFormat.TTML,
            SubtitleLimits(maxInputSize = 34),
        )
        assertEquals(listOf(listOf("kept")), input.cues.map { it.text })
        assertTrue(SubtitleTruncation.INPUT_SIZE in input.truncations)
    }

    @Test
    fun `pathological documents stay linear`() {
        val inputs = listOf(
            "<".repeat(2_000_000),
            "<tt " + "a='b' ".repeat(300_000),
            "<tt>" + "<div>".repeat(300_000),
            "<tt>" + "<style xml:id='s' style='" + "s ".repeat(300_000) + "'/>",
            "<tt><p begin='1s' end='2s'>" + "<span>".repeat(300_000),
            "<tt><p begin='1s' end='2s'>" + "&".repeat(1_000_000),
        )
        for (input in inputs) {
            val started = kotlin.time.TimeSource.Monotonic.markNow()
            SubtitleParser.parse(input, SubtitleFormat.TTML)
            assertTrue(started.elapsedNow().inWholeSeconds < 15, "took ${started.elapsedNow()} on ${input.take(30)}")
        }
    }
}
