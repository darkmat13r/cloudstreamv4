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

    /** A media container the player plays from its own bytes: MPEG-TS, MP4/fragmented MP4, Matroska/WebM. */
    Container,

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
            return when {
                text.startsWith("#EXTM3U") -> Hls
                // Any #EXT tag without #EXTM3U first is still read as a playlist by a lenient reader.
                upper.startsWith("#EXT") -> OtherPlaylist
                MANIFESTS.any { it in upper } -> Manifest
                OTHER_PLAYLISTS.any { upper.startsWith(it) } || PLAYLIST_ELEMENTS.any { it in upper } -> OtherPlaylist
                isContainer(head) -> Container
                else -> Opaque
            }
        }

        private fun isContainer(b: ByteArray): Boolean = isMpegTs(b) || isIsoBmff(b) || isMatroska(b)

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

        private const val SYNC: Byte = 0x47
        private const val TS_PACKET = 188
        private val BOX_TYPES = setOf("ftyp", "styp", "moov", "moof", "sidx", "free", "skip", "mdat", "wide", "pdin", "emsg", "prft")
        private val MANIFESTS = listOf("<MPD", "<SMOOTHSTREAMINGMEDIA", "XMLNS=\"HTTP://NS.ADOBE.COM/F4M")
        private val OTHER_PLAYLISTS = listOf("[PLAYLIST]", "#EXTINF", "RTSPTEXT", "[REFERENCE]", "REF ", "#!")
        private val PLAYLIST_ELEMENTS = listOf("<ASX", "<SMIL", "<PLAYLIST", "<MANIFEST", "<WPL", "<ZPL", "<ITEM", "<TRACKLIST", "<REF ", "<ENTRY")
    }
}
