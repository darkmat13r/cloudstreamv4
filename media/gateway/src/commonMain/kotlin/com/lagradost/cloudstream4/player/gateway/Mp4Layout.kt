package com.lagradost.cloudstream4.player.gateway

/**
 * An MP4 first url's top-level boxes, walked before the player is given it (`BUG-119`), wherever its `moov` sits: at the
 * front, or after the media at the file's tail.
 *
 * libVLC's `mp4` demuxer turns a reference movie (`moov/rmra`, its `rmda/rdrf` urls) into subitems of the stream
 * (VLC 3.0.x modules/demux/mp4/mp4.c), and a compressed movie header (`moov/cmov`, or `cmov` in a `free` box it reads
 * as a hidden `moov`) is inflated into a `moov` that can hold one (libmp4.c `MP4_BoxGetRoot`, `MP4_ReadBoxSkip`). The
 * player never plays subitems; this is the second layer, so neither alone holds the line. Refused:
 * - no `moov` found, or a second one;
 * - a `moov` larger than the bound (a size-0 `moov` runs to the end of the file, and is judged at that size);
 * - `rmra` or `cmov` anywhere in the `moov`'s bytes (a superset: libVLC reads `rmra` only as a child of `moov`);
 * - a top-level `free` box whose first child is `cmov` or `mvhd` (libVLC reads that as a hidden `moov`);
 * - a box shorter than its header, a size-0 box before the `moov` is found (nothing after it could be read), or a box
 *   that runs past the end of the file;
 * - more top-level boxes than the bound.
 *
 * It reads what libVLC would read before playing: every top-level box up to the `moov`, then the rest of the file, or,
 * for a fragmented file (a `moov/mvex`), up to the first `moof` or `mdat`. A size-0 box after the `moov` (an `mdat` to
 * the end of the file) ends the walk, as it ends libVLC's. Every byte read is kept ([Pass.pinned]): the gateway serves
 * the same bytes at those offsets or nothing, so the player reads the very boxes that were judged.
 */
internal object Mp4Layout {
    /** Why an MP4 was refused. A kind only. */
    enum class Refusal { NoMoov, SecondMoov, MoovTooLarge, ReferenceMovie, CompressedMovie, MoovInFree, BadBox, SizeZeroBeforeMoov, PastEnd, TooManyBoxes }

    sealed interface Verdict

    /** Walked and passed: the file's [total] length and every byte read, by offset. */
    class Pass(val pinned: Pinned) : Verdict

    class Refuse(val why: Refusal) : Verdict

    /**
     * The bytes read at their offsets in a file of [total] bytes. What the gateway then serves must hold the same bytes
     * at those offsets ([matches]) and be that long.
     */
    class Pinned(val total: Long, regions: List<Pair<Long, ByteArray>>) {
        private val regions = regions.filter { it.second.isNotEmpty() }.sortedBy { it.first }

        /** Whether [length] bytes of [bytes] from [from], at [offset] in the file, agree with every byte pinned there. */
        fun matches(offset: Long, bytes: ByteArray, from: Int = 0, length: Int = bytes.size - from): Boolean {
            val end = offset + length
            for ((start, pinned) in regions) {
                val stop = start + pinned.size
                if (stop <= offset || start >= end) continue
                val lo = maxOf(start, offset)
                val hi = minOf(stop, end)
                for (p in lo until hi) {
                    if (bytes[from + (p - offset).toInt()] != pinned[(p - start).toInt()]) return false
                }
            }
            return true
        }
    }

    /**
     * Walks a file of [total] bytes, reading through [read] (exactly the bytes asked for, from that offset, or a throw).
     * [maxMoovBytes] bounds the `moov` read whole; [maxBoxes] the top-level boxes walked.
     */
    suspend fun walk(total: Long, maxMoovBytes: Long, maxBoxes: Int, read: suspend (offset: Long, length: Int) -> ByteArray): Verdict {
        val pinned = mutableListOf<Pair<Long, ByteArray>>()
        suspend fun take(offset: Long, length: Int) = read(offset, length).also { pinned += offset to it }
        var moovFound = false
        var fragmented = false
        var position = 0L
        var boxes = 0
        while (position < total) {
            if (++boxes > maxBoxes) return Refuse(Refusal.TooManyBoxes)
            val left = total - position
            // libVLC needs 8 bytes for a header; fewer end its walk, and with no moov yet, the file.
            if (left < 8) return if (moovFound) Pass(Pinned(total, pinned)) else Refuse(Refusal.NoMoov)
            val head = take(position, minOf(left, HEADER_READ.toLong()).toInt())
            val short = u32(head, 0)
            val type = fourcc(head, 4)
            val size: Long = when (short) {
                0L -> when {
                    // A size-0 moov runs to the end of the file: judged at that size, against the bound.
                    type == "moov" && !moovFound -> left
                    // A size-0 box after the moov (an mdat to the end) ends the walk, as libVLC's ends there.
                    moovFound -> return Pass(Pinned(total, pinned))
                    else -> return Refuse(Refusal.SizeZeroBeforeMoov)
                }
                1L -> if (head.size < 16) return Refuse(Refusal.BadBox) else u64(head, 8) ?: return Refuse(Refusal.PastEnd)
                else -> short
            }
            if (size < (if (short == 1L) 16 else 8)) return Refuse(Refusal.BadBox)
            if (size > left) return Refuse(Refusal.PastEnd)
            when (type) {
                "moov" -> {
                    if (moovFound) return Refuse(Refusal.SecondMoov)
                    if (size > maxMoovBytes) return Refuse(Refusal.MoovTooLarge)
                    val moov = take(position, size.toInt())
                    if (contains(moov, "rmra")) return Refuse(Refusal.ReferenceMovie)
                    if (contains(moov, "cmov")) return Refuse(Refusal.CompressedMovie)
                    moovFound = true
                    fragmented = hasChild(moov, if (short == 1L) 16 else 8, "mvex")
                }
                "free" -> {
                    // libVLC peeks the type after the free box's header and 4 bytes: cmov or mvhd make it a hidden moov.
                    val at = (if (short == 1L) 16 else 8) + 4
                    if (head.size >= at + 4 && fourcc(head, at) in HIDDEN_MOOV) return Refuse(Refusal.MoovInFree)
                }
                "moof", "mdat" -> if (moovFound && fragmented) return Pass(Pinned(total, pinned))
            }
            position += size
        }
        return if (moovFound) Pass(Pinned(total, pinned)) else Refuse(Refusal.NoMoov)
    }

    /**
     * Whether [moov]'s direct children, from [start], include [type]. A child that does not fit ends the look: the file
     * is then read as not fragmented, which walks more of it, never less.
     */
    private fun hasChild(moov: ByteArray, start: Int, type: String): Boolean {
        var at = start.toLong()
        while (at + 8 <= moov.size) {
            val size = u32(moov, at.toInt())
            if (fourcc(moov, at.toInt() + 4) == type) return true
            if (size < 8) return false
            at += size
        }
        return false
    }

    private fun contains(bytes: ByteArray, fourcc: String): Boolean {
        val f = fourcc.encodeToByteArray()
        for (i in 0..bytes.size - 4) {
            if (bytes[i] == f[0] && bytes[i + 1] == f[1] && bytes[i + 2] == f[2] && bytes[i + 3] == f[3]) return true
        }
        return false
    }

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    /** A 64-bit size, or null when it does not fit a signed long (and so runs past any file). */
    private fun u64(b: ByteArray, at: Int): Long? {
        val high = u32(b, at)
        if (high >= 0x8000_0000L) return null
        return (high shl 32) or u32(b, at + 4)
    }

    private fun fourcc(b: ByteArray, at: Int) = b.copyOfRange(at, at + 4).decodeToString()

    /** A header (8, or 16 with a 64-bit size) and what libVLC peeks in a free box after it: 16 + 4 + 4. */
    private const val HEADER_READ = 24
    private val HIDDEN_MOOV = setOf("cmov", "mvhd")
}
