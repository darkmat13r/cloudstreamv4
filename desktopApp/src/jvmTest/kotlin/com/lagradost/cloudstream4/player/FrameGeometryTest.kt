package com.lagradost.cloudstream4.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The reported sizes are what libVLC 3.0.23 really reported for those files. See `BUG-15`. */
class FrameGeometryTest {
    private fun track(width: Int, height: Int, num: Int = 1, den: Int = 1, sideways: Boolean = false) =
        VideoTrackShape(width, height, num, den, sideways)

    private fun buffer(reported: FrameSize, vararg tracks: VideoTrackShape) = FrameGeometry.bufferSize(reported, tracks.toList())
    private fun picture(reported: FrameSize, vararg tracks: VideoTrackShape) = FrameGeometry.pictureSize(reported, tracks.toList())

    @Test
    fun `the buffer is the visible picture, not the decoder's padded size`() {
        val measured = listOf(
            FrameSize(1280, 738) to track(1280, 720), // H.264
            FrameSize(1280, 736) to track(1280, 720), // HEVC, VP9
            FrameSize(864, 482) to track(854, 480),
            FrameSize(640, 386) to track(640, 360),
            FrameSize(1920, 802) to track(1920, 800),
        )
        measured.forEach { (reported, track) ->
            val expected = FrameSize(track.width, track.height)
            assertEquals(expected, buffer(reported, track), "$reported")
            assertEquals(expected, picture(reported, track), "$reported")
        }
    }

    @Test
    fun `padding does not narrow a capped frame`() {
        assertEquals(FrameSize(1920, 1080), buffer(FrameSize(1920, 1090), track(1920, 1080)))
        assertEquals(FrameSize(1920, 1080), buffer(FrameSize(3840, 2178), track(3840, 2160)))
        assertEquals(FrameSize(608, 1080), buffer(FrameSize(720, 1282), track(720, 1280)))
    }

    @Test
    fun `with no track that can say, the reported size stands, capped, and there is no picture size`() {
        val noAnswer = listOf(
            emptyList(),
            listOf(track(0, 0, 0, 0)), // MPEG-TS, until after the format callback has returned
            listOf(track(1280, 720)), // the size before a change, when the report comes after the format
        )
        noAnswer.forEach { tracks ->
            assertEquals(FrameSize(864, 482), FrameGeometry.bufferSize(FrameSize(864, 482), tracks))
            assertNull(FrameGeometry.pictureSize(FrameSize(864, 482), tracks))
        }
        assertEquals(FrameSize(1902, 1080), buffer(FrameSize(1920, 1090)))
    }

    @Test
    fun `a track larger than the buffer is not the track this buffer is for`() {
        assertEquals(FrameSize(854, 482), buffer(FrameSize(854, 482), track(1280, 720)))
        assertEquals(FrameSize(854, 482), buffer(FrameSize(854, 482), track(856, 480)))
    }

    @Test
    fun `a track is believed only within the padding, on each axis alone`() {
        val reported = FrameSize(1280, 738)
        listOf(track(1280, 720), track(1214, 720), track(1280, 672)).forEach {
            assertEquals(FrameSize(it.width, it.height), buffer(reported, it), "$it")
        }
        val tooSmallOrTooLarge = listOf(track(1213, 720), track(1280, 671), track(1282, 720), track(1280, 740))
        tooSmallOrTooLarge.forEach {
            assertEquals(reported, buffer(reported, it), "$it")
            assertNull(picture(reported, it), "$it")
        }
    }

    @Test
    fun `of several tracks, the one this buffer can hold is used`() {
        val tracks = arrayOf(track(1920, 1080), track(1280, 720))
        assertEquals(FrameSize(1280, 720), buffer(FrameSize(1280, 738), *tracks))
    }

    @Test
    fun `non-square pixels shape the picture and never the buffer`() {
        val pal = track(720, 576, 16, 15)
        assertEquals(FrameSize(720, 576), buffer(FrameSize(720, 578), pal))
        assertEquals(FrameSize(768, 576), picture(FrameSize(720, 578), pal))

        val hdv = track(1440, 1080, 4, 3)
        assertEquals(FrameSize(1440, 1080), buffer(FrameSize(1440, 1090), hdv))
        assertEquals(FrameSize(1920, 1080), picture(FrameSize(1440, 1090), hdv))
    }

    @Test
    fun `narrow pixels make the picture taller rather than narrower`() {
        assertEquals(FrameSize(720, 540), picture(FrameSize(720, 482), track(720, 480, 8, 9)))
    }

    @Test
    fun `a picture past the cap keeps its shape`() {
        // 1920 x 1080 at 2:1 is 3840 x 1080, so the width is what binds.
        assertEquals(FrameSize(1920, 540), picture(FrameSize(1920, 1090), track(1920, 1080, 2, 1)))
    }

    @Test
    fun `a track shown sideways is matched and measured upright`() {
        val rotated = track(1280, 720, sideways = true)
        assertEquals(FrameSize(608, 1080), buffer(FrameSize(738, 1280), rotated))
        assertEquals(FrameSize(608, 1080), picture(FrameSize(738, 1280), rotated))
        // The pixels turn with it, so 16:15 wide becomes 16:15 tall.
        assertEquals(FrameSize(576, 768), picture(FrameSize(578, 720), track(720, 576, 16, 15, sideways = true)))
    }

    @Test
    fun `an absurd or missing sample aspect ratio means square pixels`() {
        val reported = FrameSize(1280, 738)
        val square = FrameSize(1280, 720)
        val absurd = listOf(0 to 0, 0 to 1, 1 to 0, -1 to 1, 1 to -1, 1000 to 1, 1 to 1000, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE, Int.MIN_VALUE to -1)
        absurd.forEach { (num, den) ->
            assertEquals(square, picture(reported, track(1280, 720, num, den)), "$num:$den")
        }
    }

    @Test
    fun `whatever a file claims, both sizes are inside the bounds the app enforces`() {
        val values = listOf(Int.MIN_VALUE, -1, 0, 1, 2, 64, 720, 738, 1080, 1090, 1920, 1921, 4096, 65535, Int.MAX_VALUE)
        val aspects = listOf(0, 1, 2, 3, 4, 5, 16, 1000, Int.MAX_VALUE, -3)
        for (width in values) for (height in values) for (num in aspects) for (den in aspects) {
            val reported = FrameSize(width, height)
            val tracks = listOf(track(width, height, num, den), track(width - 2, height - 18, num, den, sideways = true))
            val sizes = listOfNotNull(FrameGeometry.bufferSize(reported, tracks), FrameGeometry.pictureSize(reported, tracks))
            sizes.forEach { size ->
                val within = size.width in 1..MediaProtocol.MAX_FRAME_WIDTH && size.height in 1..MediaProtocol.MAX_FRAME_HEIGHT
                assertTrue(within, "$size from $reported $tracks")
            }
        }
    }
}
