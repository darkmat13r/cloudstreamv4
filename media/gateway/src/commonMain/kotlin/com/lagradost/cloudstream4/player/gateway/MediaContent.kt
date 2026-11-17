package com.lagradost.cloudstream4.player.gateway

/**
 * What a response is, told by its first bytes and never by its `Content-Type` or the url's extension (`BUG-114`). A
 * real provider serves its HLS playlist as `text/html`; a hostile one could serve a playlist as `video/mp2t`.
 */
internal enum class MediaContent {
    /** An HLS playlist: rewritten, `strict`, before the player sees it. */
    Hls,

    /** A manifest the gateway does not rewrite (DASH, Smooth Streaming, HDS): refused, since the player would follow it. */
    Manifest,

    /** A playlist format the player would read urls from (pls, xspf, asx, smil, a plain m3u, …): refused. */
    OtherPlaylist,

    /** MPEG-TS: the sync byte at 0, and at 188 when there are that many bytes. libVLC's `ts` demuxer. */
    MpegTs,

    /** ISO BMFF (MP4, fragmented MP4): a box a file or a fragment starts with. libVLC's `mp4` demuxer. */
    Mp4,

    /** Matroska or WebM: the EBML magic. libVLC's `mkv` demuxer. */
    Matroska,

    /** None of the above: bytes whose kind is not known. A key or an encrypted segment looks like this. */
    Opaque;

    companion object {
        /** How many first bytes [of] reads. */
        const val SNIFF_BYTES = 4096

        /** [head], the first bytes of a response (up to [SNIFF_BYTES]), classified. */
        fun of(head: ByteArray): MediaContent {
            val text = head.copyOf(minOf(head.size, SNIFF_BYTES)).decodeToString().removePrefix("\uFEFF").trimStart()
            // The whole sniff window, not its start: a manifest can sit behind bytes that look like a container's, and a
            // player that probes content would still find it.
            val upper = text.uppercase()
            // libVLC's HDS reader (a stream filter, so no forced demuxer stops it, and it fetches by itself) decodes a
            // body with a UTF-16 byte-order mark before it looks for `<manifest` (`isHDS`, VLC 3.0.x
            // modules/stream_filter/hds/hds.c), and an XML playlist reader can too. Such a body is read the same way here:
            // as UTF-16, it is a manifest or a playlist by the same marks, and never HLS, which the rewriter reads as UTF-8.
            utf16(head)?.let { wide ->
                val wideUpper = wide.uppercase()
                when {
                    MANIFESTS.any { it in wideUpper } -> return Manifest
                    wideUpper.trimStart().startsWith("#EXT") || OTHER_PLAYLISTS.any { wideUpper.trimStart().startsWith(it) } ||
                        PLAYLIST_ELEMENTS.any { it in wideUpper } || wide.split('\n').any { URL_LINE.containsMatchIn(it) } -> return OtherPlaylist
                }
            }
            return when {
                text.startsWith("#EXTM3U") -> Hls
                // Any #EXT tag without #EXTM3U first is still read as a playlist by a lenient reader.
                upper.startsWith("#EXT") -> OtherPlaylist
                MANIFESTS.any { it in upper } -> Manifest
                OTHER_PLAYLISTS.any { upper.startsWith(it) } || PLAYLIST_ELEMENTS.any { it in upper } -> OtherPlaylist
                // libVLC's m3u reader takes any input with a line that starts with a url (`ContainsURL`, VLC 3.0.x
                // modules/demux/playlist/m3u.c), whatever came before: a "TS" with a sync byte at 0 and 188 and a
                // `https://…` line between them is a playlist to it.
                text.split('\n').any { URL_LINE.containsMatchIn(it) } -> OtherPlaylist
                isMpegTs(head) -> MpegTs
                isIsoBmff(head) -> Mp4
                isMatroska(head) -> Matroska
                else -> Opaque
            }
        }

        /**
         * [head] as UTF-16 text, or null when it does not look like UTF-16. A superset of what libVLC decodes:
         * - a UTF-16 byte-order mark (`FF FE` little-endian, `FE FF` big), as `isHDS` reads one;
         * - the same after a UTF-8 byte-order mark;
         * - no mark, with a zero byte at 1 and 3 (little-endian) or 0 and 2 (big): libVLC's readers stop at the first zero
         *   byte (`isHDS` copies with `strndup`, `ContainsURL` stops at a byte <= 0), so none reads such a body as text,
         *   but an XML parser might; it costs nothing to look.
         * Read to an even count within the sniff window: an odd last byte is dropped, never an error.
         */
        private fun utf16(head: ByteArray): String? {
            val window = head.copyOf(minOf(head.size, SNIFF_BYTES))
            fun at(i: Int, v: Int) = i < window.size && window[i] == v.toByte()
            val utf8Bom = at(0, 0xEF) && at(1, 0xBB) && at(2, 0xBF)
            val o = if (utf8Bom) 3 else 0
            val (little, start) = when {
                at(o, 0xFF) && at(o + 1, 0xFE) -> true to o + 2
                at(o, 0xFE) && at(o + 1, 0xFF) -> false to o + 2
                !utf8Bom && window.size >= 4 && at(1, 0) && at(3, 0) && !at(0, 0) -> true to 0
                !utf8Bom && window.size >= 4 && at(0, 0) && at(2, 0) && !at(1, 0) -> false to 0
                else -> return null
            }
            val pairs = (window.size - start) / 2
            if (pairs <= 0) return null
            val chars = CharArray(pairs) { i ->
                val a = window[start + 2 * i].toInt() and 0xFF
                val b = window[start + 2 * i + 1].toInt() and 0xFF
                (if (little) (b shl 8) or a else (a shl 8) or b).toChar()
            }
            return chars.concatToString()
        }

        /** The container kinds: what a stream's first url may be besides HLS. */
        val CONTAINERS = setOf(MpegTs, Mp4, Matroska)

        /**
         * The libVLC demuxer (VLC 3.0.x module name) for [kind], so the player is told which to use rather than guess:
         * `adaptive` for HLS, `ts`, `mp4` or `mkv` for a container; null for anything else.
         */
        fun demuxFor(kind: MediaContent): String? = when (kind) {
            Hls -> "adaptive"
            MpegTs -> "ts"
            Mp4 -> "mp4"
            Matroska -> "mkv"
            else -> null
        }

        /** [demuxFor] the kind of [head]. */
        fun demuxOf(head: ByteArray): String? = demuxFor(of(head)).takeIf { of(head) in CONTAINERS }

        /** MPEG-TS: the sync byte at 0, and at 188 when there are that many bytes. */
        private fun isMpegTs(b: ByteArray) = b.isNotEmpty() && b[0] == SYNC && (b.size <= TS_PACKET || b[TS_PACKET] == SYNC)

        /** ISO BMFF (MP4, fragmented MP4): a box whose type is one a file or a fragment starts with. */
        private fun isIsoBmff(b: ByteArray): Boolean {
            if (b.size < 8) return false
            val type = b.copyOfRange(4, 8).decodeToString()
            return type in BOX_TYPES
        }

        /** Matroska and WebM: the EBML magic. */
        private fun isMatroska(b: ByteArray) =
            b.size >= 4 && b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() && b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte()

        /** A line that starts with a url, as `ContainsURL` reads one (its 7 schemes are 3 to 5 letters): any scheme. */
        private val URL_LINE = Regex("^[A-Za-z][A-Za-z0-9+.-]{0,5}://")

        private const val SYNC: Byte = 0x47
        private const val TS_PACKET = 188
        private val BOX_TYPES = setOf("ftyp", "styp", "moov", "moof", "sidx", "free", "skip", "mdat", "wide", "pdin", "emsg", "prft")
        private val MANIFESTS = listOf("<MPD", "<SMOOTHSTREAMINGMEDIA", "XMLNS=\"HTTP://NS.ADOBE.COM/F4M")
        private val OTHER_PLAYLISTS = listOf("[PLAYLIST]", "#EXTINF", "RTSPTEXT", "[REFERENCE]", "REF ", "#!")
        private val PLAYLIST_ELEMENTS = listOf("<ASX", "<SMIL", "<PLAYLIST", "<MANIFEST", "<WPL", "<ZPL", "<ITEM", "<TRACKLIST", "<REF ", "<ENTRY")
    }
}
