package com.lagradost.cloudstream4.player.gateway

import kotlin.coroutines.cancellation.CancellationException

/**
 * `KMP-130`: a downloaded file, judged as the media gateway judges a stream's first url (`BUG-114`, `BUG-119`), for a
 * player that reads the file itself.
 *
 * The iPad's libVLC runs in the app, and the gateway is what keeps it from following a url a stream names: a playlist,
 * a manifest, an MP4 reference movie. A file played from the disk does not pass through the gateway (the owner's
 * exception, for the downloads folder only), so its bytes are held to the same rules here before the player sees it:
 * - its first bytes ([MediaContent.SNIFF_BYTES]) must be a container, MPEG-TS, MP4 or Matroska: never HLS, which would
 *   name segments, nor a manifest, another playlist, or bytes of no known kind;
 * - an MP4's top-level boxes are walked ([Mp4Layout]) with the gateway's bounds, so a reference or compressed movie, or
 *   a layout that could hide one, is refused.
 *
 * What it does not do, and the gateway does: pin the judged bytes. The gateway serves a stream it fetches again, so it
 * holds every later read to the bytes it judged; a file in the app's own downloads folder is written only by the
 * download queue, and not again once it is done.
 */
object LocalMediaCheck {
    /** The gateway's bound on an MP4's `moov`, read whole to be judged. Mirrors `IosMediaGateway.Limits.maxMoovBytes`. */
    const val MAX_MOOV_BYTES: Long = 16L * 1024 * 1024

    /** The gateway's bound on an MP4's top-level boxes. Mirrors `IosMediaGateway.Limits.maxTopLevelBoxes`. */
    const val MAX_TOP_LEVEL_BOXES: Int = 64

    /**
     * The libVLC demuxer to force for a file of [total] bytes (VLC 3.0.x module names): `ts`, `mp4` or `mkv`; null when
     * the gateway would not serve it as a first url, when it is empty, or when [read] fails.
     *
     * @param read exactly [length] bytes from [offset], or a throw.
     */
    suspend fun demuxOf(
        total: Long,
        read: suspend (offset: Long, length: Int) -> ByteArray,
        maxMoovBytes: Long = MAX_MOOV_BYTES,
        maxTopLevelBoxes: Int = MAX_TOP_LEVEL_BOXES,
    ): String? {
        if (total <= 0L) return null
        return try {
            val head = read(0L, minOf(total, MediaContent.SNIFF_BYTES.toLong()).toInt())
            val kind = MediaContent.of(head)
            when {
                kind !in MediaContent.CONTAINERS -> null
                kind == MediaContent.Mp4 && Mp4Layout.walk(total, maxMoovBytes, maxTopLevelBoxes) { offset, length ->
                    if (offset + length <= head.size) head.copyOfRange(offset.toInt(), offset.toInt() + length) else read(offset, length)
                } !is Mp4Layout.Pass -> null
                else -> MediaContent.demuxFor(kind)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
