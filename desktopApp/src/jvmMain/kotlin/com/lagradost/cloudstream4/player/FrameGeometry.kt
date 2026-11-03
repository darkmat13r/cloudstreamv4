package com.lagradost.cloudstream4.player

import kotlin.math.roundToInt

internal data class FrameSize(val width: Int, val height: Int)

/**
 * What libVLC says one video track holds: the visible picture in stored pixels, and the shape of those pixels.
 *
 * @param sideways the container shows the track turned a quarter. libVLC turns the frames, and the size it reports
 *   for the buffer, but not this size. See `BUG-15`.
 */
internal data class VideoTrackShape(
    val width: Int,
    val height: Int,
    val sampleAspectNum: Int,
    val sampleAspectDen: Int,
    val sideways: Boolean = false,
)

/**
 * Which size of frame to ask libVLC for, and what shape to draw it in. Pure, so it can be tested without libVLC.
 *
 * libVLC reports the decoder's buffer, which is larger than the picture: 1280 x 738 for 1280 x 720 in software H.264.
 * Whatever size comes back, it scales the picture to fill. So answering with the reported size stretches the picture,
 * and only answering with the visible size leaves it alone. See `BUG-15` for the measurements.
 *
 * The visible size is in the track, when the track has it yet. For MPEG-TS it does not until after the format callback
 * has returned, so a buffer of the reported size cannot always be avoided. [pictureSize] then corrects the shape when
 * the frame is drawn.
 */
internal object FrameGeometry {
    /**
     * The most a decoder's buffer has been taken to exceed its picture, on each axis. The largest measured is 26, and
     * libavcodec aligns to at most 64 and adds 2. Beyond this a track is a different picture, such as the one before a
     * size change, whose report arrives after the new format.
     */
    private const val MAX_BUFFER_PADDING = 66

    /** Past this a sample aspect ratio is a broken or hostile file, and the pixels are taken as square. */
    private const val MAX_SAMPLE_ASPECT = 4.0

    /** The size to return to libVLC: the visible picture if a track has it, and never over the cap. */
    fun bufferSize(reported: FrameSize, tracks: List<VideoTrackShape>): FrameSize {
        val track = matching(reported, tracks)?.upright()
        return capped((track?.width ?: reported.width).toDouble(), (track?.height ?: reported.height).toDouble())
    }

    /** The size to draw the frame at, in square pixels and under the cap. Null until a track can say. */
    fun pictureSize(reported: FrameSize, tracks: List<VideoTrackShape>): FrameSize? {
        val track = matching(reported, tracks)?.upright() ?: return null
        val aspect = track.sampleAspect()
        // Whichever axis grows, so that non-square pixels never cost resolution before the cap.
        return if (aspect >= 1.0) capped(track.width * aspect, track.height.toDouble())
        else capped(track.width.toDouble(), track.height / aspect)
    }

    private fun matching(reported: FrameSize, tracks: List<VideoTrackShape>): VideoTrackShape? =
        tracks.firstOrNull { track ->
            val upright = track.upright()
            reported.width - upright.width in 0..MAX_BUFFER_PADDING &&
                reported.height - upright.height in 0..MAX_BUFFER_PADDING &&
                upright.width > 0 && upright.height > 0
        }

    private fun VideoTrackShape.upright(): VideoTrackShape =
        if (sideways) VideoTrackShape(height, width, sampleAspectDen, sampleAspectNum) else this

    private fun VideoTrackShape.sampleAspect(): Double {
        if (sampleAspectNum <= 0 || sampleAspectDen <= 0) return 1.0
        val aspect = sampleAspectNum.toDouble() / sampleAspectDen
        return if (aspect in (1 / MAX_SAMPLE_ASPECT)..MAX_SAMPLE_ASPECT) aspect else 1.0
    }

    private fun capped(width: Double, height: Double): FrameSize {
        val scale = minOf(1.0, MediaProtocol.MAX_FRAME_WIDTH / width, MediaProtocol.MAX_FRAME_HEIGHT / height)
        return FrameSize(
            (width * scale).roundToInt().coerceIn(1, MediaProtocol.MAX_FRAME_WIDTH),
            (height * scale).roundToInt().coerceIn(1, MediaProtocol.MAX_FRAME_HEIGHT),
        )
    }
}
