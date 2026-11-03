package com.lagradost.cloudstream4.media.subtitle

import com.lagradost.cloudstream4.media.model.CueAnchor
import com.lagradost.cloudstream4.media.model.CuePlacement
import com.lagradost.cloudstream4.media.model.CueTextAlign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Where the file asked a cue to go. The shapes are the ones the real plugins were measured sending (`KMP-25`). */
class CuePlacementTest {

    private fun srtPlacement(text: String) =
        SubtitleParser.parse("00:00:01,000 --> 00:00:02,000\n$text\n", SubtitleFormat.SRT).cues.single().placement

    private fun vttPlacement(settings: String) =
        SubtitleParser.parse("WEBVTT\n\n00:00:01.000 --> 00:00:02.000 $settings\nx\n", SubtitleFormat.VTT).cues.single().placement

    @Test
    fun `every an code lands on its numpad cell`() {
        val expected = mapOf(
            1 to Triple(0f, 100f, CueTextAlign.LEFT),
            2 to Triple(50f, 100f, CueTextAlign.CENTER),
            3 to Triple(100f, 100f, CueTextAlign.RIGHT),
            4 to Triple(0f, 50f, CueTextAlign.LEFT),
            5 to Triple(50f, 50f, CueTextAlign.CENTER),
            6 to Triple(100f, 50f, CueTextAlign.RIGHT),
            7 to Triple(0f, 0f, CueTextAlign.LEFT),
            8 to Triple(50f, 0f, CueTextAlign.CENTER),
            9 to Triple(100f, 0f, CueTextAlign.RIGHT),
        )
        for ((code, cell) in expected) {
            val placement = srtPlacement("{\\an$code}words")
            assertEquals(cell.first, placement.horizontalPercent, "an$code x")
            assertEquals(cell.second, placement.verticalPercent, "an$code y")
            assertEquals(cell.third, placement.textAlign, "an$code align")
        }
    }

    @Test
    fun `an anchors keep the box on the screen side of its point`() {
        val topLeft = srtPlacement("{\\an7}x")
        assertEquals(CueAnchor.START, topLeft.horizontalAnchor)
        assertEquals(CueAnchor.START, topLeft.verticalAnchor)
        val bottomRight = srtPlacement("{\\an3}x")
        assertEquals(CueAnchor.END, bottomRight.horizontalAnchor)
        assertEquals(CueAnchor.END, bottomRight.verticalAnchor)
    }

    @Test
    fun `the an tag leaves the text and works in a block with other tags and on a later line`() {
        val cue = SubtitleParser.parse(
            "00:00:01,000 --> 00:00:02,000\nfirst\n{\\b1\\an8\\i1}second\n",
            SubtitleFormat.SRT,
        ).cues.single()
        assertEquals(listOf("first", "second"), cue.text)
        assertEquals(CuePlacement.fromSsaAlignment(8), cue.placement)
    }

    @Test
    fun `no an, or one out of range, or never closed, stays at the bottom centre`() {
        assertEquals(CuePlacement.Default, srtPlacement("plain"))
        assertEquals(CuePlacement.Default, srtPlacement("{\\an0}x"))
        assertEquals(CuePlacement.Default, srtPlacement("{\\an10}x"))
        assertEquals(CuePlacement.Default, srtPlacement("{\\an}x"))
        assertEquals(CuePlacement.Default, srtPlacement("{\\an8 never closed"))
        assertEquals(CuePlacement.Default, srtPlacement("\\an8 is not in a block"))
    }

    @Test
    fun `the first valid an wins`() {
        assertEquals(CuePlacement.fromSsaAlignment(7), srtPlacement("{\\an7}a{\\an3}b"))
        assertEquals(CuePlacement.fromSsaAlignment(3), srtPlacement("{\\an11}a{\\an3}b"))
    }

    @Test
    fun `the numpad mapping refuses anything else`() {
        assertNull(CuePlacement.fromSsaAlignment(0))
        assertNull(CuePlacement.fromSsaAlignment(10))
        assertEquals(CuePlacement.Default, CuePlacement.fromSsaAlignment(2))
    }

    @Test
    fun `vtt line as a percentage, with each alignment`() {
        val top = vttPlacement("line:10%")
        assertEquals(10f, top.verticalPercent)
        assertEquals(CueAnchor.START, top.verticalAnchor)
        assertEquals(CueAnchor.MIDDLE, vttPlacement("line:50%,center").verticalAnchor)
        assertEquals(CueAnchor.END, vttPlacement("line:90%,end").verticalAnchor)
    }

    @Test
    fun `vtt line as a row counts from the top, and from the bottom when negative`() {
        val first = vttPlacement("line:0")
        assertEquals(0f, first.verticalPercent)
        assertEquals(CueAnchor.START, first.verticalAnchor)
        val last = vttPlacement("line:-1")
        assertEquals(100f, last.verticalPercent)
        assertEquals(CueAnchor.END, last.verticalAnchor)
        assertEquals(true, vttPlacement("line:-2").verticalPercent < 100f)
        assertEquals(true, vttPlacement("line:2").verticalPercent > 0f)
    }

    @Test
    fun `vtt position and align, as the Internet Archive's YouTube captions use them`() {
        val placement = vttPlacement("align:start position:0%")
        assertEquals(0f, placement.horizontalPercent)
        assertEquals(CueAnchor.START, placement.horizontalAnchor)
        assertEquals(CueTextAlign.START, placement.textAlign)
        assertEquals(100f, placement.verticalPercent)
    }

    @Test
    fun `vtt align middle is what ARD sends and it is the centre`() {
        assertEquals(CuePlacement.Default, vttPlacement("align:middle"))
    }

    @Test
    fun `vtt position alignment overrides the one align implies`() {
        val placement = vttPlacement("align:start position:40%,line-right")
        assertEquals(40f, placement.horizontalPercent)
        assertEquals(CueAnchor.END, placement.horizontalAnchor)
        assertEquals(CueTextAlign.START, placement.textAlign)
    }

    @Test
    fun `vtt align end alone sits at the right`() {
        val placement = vttPlacement("align:end")
        assertEquals(100f, placement.horizontalPercent)
        assertEquals(CueAnchor.END, placement.horizontalAnchor)
    }

    @Test
    fun `vtt size bounds the box`() {
        assertEquals(35f, vttPlacement("size:35%").widthPercent)
    }

    @Test
    fun `hostile settings cost only themselves`() {
        for (settings in listOf(
            "line:NaN%", "line:Infinity%", "position:-5%", "position:NaN%", "size:1e9%", "align:sideways",
            "line:", ":", "::::", "line:99999999999999999999", "region:x vertical:rl", "line:%",
        )) {
            assertEquals(CuePlacement.Default, vttPlacement(settings), settings)
        }
    }

    @Test
    fun `out of range values are clamped to the picture`() {
        assertEquals(100f, vttPlacement("position:400%").horizontalPercent)
        assertEquals(100f, vttPlacement("size:400%").widthPercent)
        assertEquals(0f, vttPlacement("line:-500").verticalPercent)
    }

    @Test
    fun `an srt never reads vtt settings, and an in text an beats them in a vtt`() {
        val srt = SubtitleParser.parse("00:00:01,000 --> 00:00:02,000 line:0\nx\n", SubtitleFormat.SRT).cues.single()
        assertEquals(CuePlacement.Default, srt.placement)
        val vtt = SubtitleParser.parse("WEBVTT\n\n00:00:01.000 --> 00:00:02.000 line:0\n{\\an3}x\n", SubtitleFormat.VTT).cues.single()
        assertEquals(CuePlacement.fromSsaAlignment(3), vtt.placement)
    }

    @Test
    fun `a line of spaces after the timing does not lose the cue`() {
        // The first cue of every YouTube-derived VTT on the Internet Archive has this shape.
        val cues = SubtitleParser.parse(
            "WEBVTT\nKind: captions\nLanguage: en\n\n" +
                "00:00:39.520 --> 00:00:41.869 align:start position:0%\n \n" +
                "good<00:00:39.719><c> morning</c>\n\n" +
                "00:00:41.869 --> 00:00:41.879 align:start position:0%\ngood morning\n \n\n",
        ).cues
        assertEquals(listOf(listOf("good morning"), listOf("good morning")), cues.map { it.text })
    }

    @Test
    fun `an empty line right after the timing is still an empty cue`() {
        val cues = SubtitleParser.parse("00:00:01,000 --> 00:00:02,000\n\nnot a cue\n", SubtitleFormat.SRT).cues
        assertEquals(emptyList(), cues)
    }
}
