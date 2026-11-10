package com.lagradost.cloudstream4.player

import java.io.File
import java.nio.file.Path

/**
 * A downloaded file the app has opened read-only, to hand to one media host (`KMP-74`). The owner's ruling: the host
 * gets just this one file, as an open descriptor. Its sandbox does not widen, and it never sees a folder or a path.
 *
 * Owned by whoever opened it, and closed by [VlcPlayer.createOrNull] the moment it has been sent, sent or not.
 */
internal class LocalMedia private constructor(fd: Int) : AutoCloseable {
    private var open: Int? = fd

    /** The descriptor, until [close]. */
    val fd: Int get() = checkNotNull(open) { "closed" }

    override fun close() {
        open?.let(UnixFds::close)
        open = null
    }

    companion object {
        /**
         * Opens [file] if it is a regular file inside [root], reached without a link at any step below [root]. Null
         * otherwise, with nothing left open.
         *
         * Checked twice: by path first, and then on the open descriptor, by asking the kernel what it is (a regular
         * file) and where (still inside [root]). So a folder or a link swapped in between the two is refused too.
         */
        fun open(file: File, root: File): LocalMedia? {
            val expected = expectedPath(file, root) ?: return null
            val fd = UnixFds.openReadOnly(expected.toString()) ?: return null
            val opened = runCatching { UnixFds.isRegularFile(fd) && UnixFds.pathOf(fd) == expected.toString() }
                .getOrDefault(false)
            if (!opened) {
                UnixFds.close(fd)
                return null
            }
            return LocalMedia(fd)
        }

        /**
         * Where [file] really is, if that is inside [root] by the same steps it was named with; null when it is not,
         * when a step is a link, or when it does not exist.
         */
        internal fun expectedPath(file: File, root: File): Path? = runCatching {
            val named = root.toPath().toAbsolutePath().normalize()
            val relative = named.relativize(file.toPath().toAbsolutePath().normalize())
            if (relative.toString().isEmpty() || relative.startsWith("..")) return null
            val realRoot = named.toRealPath()
            val expected = realRoot.resolve(relative)
            // toRealPath follows every link on the way. Equal only if none below the root was one.
            expected.takeIf { it.toRealPath() == it }
        }.getOrNull()
    }
}
