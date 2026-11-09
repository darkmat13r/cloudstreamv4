package com.lagradost.cloudstream4.player

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * The few macOS calls `KMP-74` needs to hand the media host one open file and nothing else: a socket pair, and an
 * `SCM_RIGHTS` message across it. The app opens the file; the host only ever holds the descriptor.
 *
 * macOS only, as the media host is ([VlcPlayer.createOrNull] is null anywhere `sandbox-exec` is not). The structure
 * layouts below are the 64-bit Darwin ones, written out by hand because JNA has none for them; `UnixFdsTest` runs
 * each call against the real kernel.
 */
internal object UnixFds {
    private interface C : Library {
        fun open(path: String, flags: Int): Int
        fun close(fd: Int): Int
        fun socketpair(domain: Int, type: Int, protocol: Int, fds: IntArray): Int
        fun sendmsg(fd: Int, message: Pointer, flags: Int): Long
        fun recvmsg(fd: Int, message: Pointer, flags: Int): Long

        /** Variadic in C, and declared so here: on Apple silicon a variadic argument goes on the stack. */
        fun fcntl(fd: Int, command: Int, vararg argument: Any?): Int
    }

    private val c: C by lazy { Native.load("c", C::class.java) }

    /** `fstat` with 64-bit inodes. On Intel the plain symbol is the old layout, which puts `st_mode` elsewhere. */
    private val fstat by lazy {
        val symbol = if (System.getProperty("os.arch") == "aarch64") "fstat" else "fstat\$INODE64"
        NativeLibrary.getInstance("c").getFunction(symbol)
    }

    /** Read-only, no link at the last step, no wait on a FIFO, and not inherited by anything this JVM starts. */
    fun openReadOnly(path: String): Int? = c.open(path, O_RDONLY or O_NOFOLLOW or O_NONBLOCK or O_CLOEXEC).takeIf { it >= 0 }

    fun close(fd: Int) {
        c.close(fd)
    }

    fun isRegularFile(fd: Int): Boolean {
        val stat = Memory(STAT_SIZE)
        if (fstat.invokeInt(arrayOf(fd, stat)) != 0) return false
        return stat.getShort(STAT_MODE_OFFSET).toInt() and S_IFMT == S_IFREG
    }

    /** What the kernel says [fd] is open on. For the app's own check, before the descriptor leaves it. */
    fun pathOf(fd: Int): String? {
        val buffer = Memory(MAXPATHLEN.toLong())
        if (c.fcntl(fd, F_GETPATH, buffer) != 0) return null
        return buffer.getString(0, "UTF-8")
    }

    /** Two connected stream sockets, or null. */
    fun socketPair(): Pair<Int, Int>? {
        val fds = IntArray(2)
        if (c.socketpair(AF_UNIX, SOCK_STREAM, 0, fds) != 0) return null
        return fds[0] to fds[1]
    }

    /** Sends [passed] across [socket] with one marker byte. The kernel holds its own reference until it is received. */
    fun send(socket: Int, passed: Int): Boolean = send(socket, intArrayOf(passed))

    /** As [send], with several descriptors in one message. The app sends one; this is how a test sends more. */
    internal fun send(socket: Int, passed: IntArray): Boolean = send(socket, listOf(passed))

    /**
     * As [send], with one `SCM_RIGHTS` control message for each of [groups]. The app never sends more than one; this is
     * how a test asks the kernel to deliver two.
     */
    internal fun send(socket: Int, groups: List<IntArray>): Boolean {
        val total = groups.sumOf { align(CMSG_HEADER + it.size * FD_BYTES) }
        require(groups.isNotEmpty() && groups.all { it.isNotEmpty() } && total <= CONTROL_BYTES) { "too many descriptors" }
        val message = Message()
        var offset = 0L
        for (group in groups) {
            val length = CMSG_HEADER + group.size * FD_BYTES
            message.control.setInt(offset + CMSG_LEN_OFFSET, length)
            message.control.setInt(offset + CMSG_LEVEL_OFFSET, SOL_SOCKET)
            message.control.setInt(offset + CMSG_TYPE_OFFSET, SCM_RIGHTS)
            group.forEachIndexed { index, fd -> message.control.setInt(offset + CMSG_DATA_OFFSET + index * FD_BYTES, fd) }
            offset += align(length)
        }
        message.header.setInt(MSG_CONTROLLEN_OFFSET, total)
        return c.sendmsg(socket, message.header, 0) == 1L
    }

    /**
     * Receives what [send] sent: exactly one descriptor, with its marker byte, or null. Reads one byte only, and must be
     * the first read of [socket]: a plain read of that byte would drop the descriptor with it.
     */
    fun receive(socket: Int): Int? {
        val message = Message()
        val read = c.recvmsg(socket, message.header, 0)
        val choice = choose(
            message.control,
            message.header.getInt(MSG_CONTROLLEN_OFFSET),
            truncated = message.header.getInt(MSG_FLAGS_OFFSET) and MSG_CTRUNC != 0,
        )
        // Every descriptor that arrived is already open in this process, and nothing else would ever close it.
        choice.refused.forEach(::close)
        if (read != 1L) {
            choice.taken?.let(::close)
            return null
        }
        return choice.taken
    }

    /** What [receive] keeps, and what it must close. */
    internal class Choice(val taken: Int?, val refused: List<Int>)

    /**
     * Reads every control message in the first [length] bytes of [control], as the kernel left them. Takes a
     * descriptor only when there is exactly one message, `SCM_RIGHTS`, carrying exactly one, and that message is the
     * whole of [length]: so a second message cannot bring descriptors past the check. Everything else is refused.
     */
    internal fun choose(control: Pointer, length: Int, truncated: Boolean): Choice {
        val messages = mutableListOf<Pair<Int, List<Int>>>()
        val end = length.coerceIn(0, CONTROL_BYTES.toInt())
        var offset = 0
        while (offset + CMSG_HEADER <= end) {
            val size = control.getInt(offset + CMSG_LEN_OFFSET)
            if (size < CMSG_HEADER || offset + size > end) break
            val rights = control.getInt(offset + CMSG_LEVEL_OFFSET) == SOL_SOCKET &&
                control.getInt(offset + CMSG_TYPE_OFFSET) == SCM_RIGHTS
            val fds = if (rights) {
                List((size - CMSG_HEADER) / FD_BYTES) { control.getInt(offset + CMSG_DATA_OFFSET + it * FD_BYTES) }
            } else {
                emptyList()
            }
            messages += size to fds.filter { it >= 0 }
            offset += align(size)
        }
        val all = messages.flatMap { it.second }
        val only = messages.singleOrNull()
        val whole = only != null && length == align(only.first) && only.second.size == 1 &&
            only.first == CMSG_HEADER + FD_BYTES
        return if (!truncated && whole) Choice(all.single(), emptyList()) else Choice(null, all)
    }

    /** A `msghdr` with one byte of data and [CONTROL_BYTES] of room for control messages. */
    private class Message {
        val data = Memory(1).apply { setByte(0, MARKER) }
        val vector = Memory(IOVEC_SIZE).apply {
            setPointer(0, data)
            setLong(Native.POINTER_SIZE.toLong(), 1)
        }
        val control = Memory(CONTROL_BYTES).apply { clear() }
        val header = Memory(MSGHDR_SIZE).apply {
            clear()
            setPointer(MSG_IOV_OFFSET, vector)
            setInt(MSG_IOVLEN_OFFSET, 1)
            setPointer(MSG_CONTROL_OFFSET, control)
            setInt(MSG_CONTROLLEN_OFFSET, CONTROL_BYTES.toInt())
        }
    }

    private const val MARKER: Byte = 'F'.code.toByte()

    // <fcntl.h>, <sys/stat.h>, <sys/socket.h> on Darwin.
    private const val O_RDONLY = 0x0000
    private const val O_NONBLOCK = 0x0004
    private const val O_NOFOLLOW = 0x0100
    private const val O_CLOEXEC = 0x01000000
    private const val F_GETPATH = 50
    private const val MAXPATHLEN = 1024
    private const val S_IFMT = 0xF000
    private const val S_IFREG = 0x8000
    private const val STAT_SIZE = 144L
    private const val STAT_MODE_OFFSET = 4L
    private const val AF_UNIX = 1
    private const val SOCK_STREAM = 1
    private const val SOL_SOCKET = 0xFFFF
    private const val SCM_RIGHTS = 1
    private const val MSG_CTRUNC = 0x20

    // struct msghdr: name, namelen, iov, iovlen, control, controllen, flags.
    private const val MSGHDR_SIZE = 48L
    private const val MSG_IOV_OFFSET = 16L
    private const val MSG_IOVLEN_OFFSET = 24L
    private const val MSG_CONTROL_OFFSET = 32L
    private const val MSG_CONTROLLEN_OFFSET = 40L
    private const val MSG_FLAGS_OFFSET = 44L
    private const val IOVEC_SIZE = 16L

    // struct cmsghdr: len, level, type, then the data, 4-byte aligned on Darwin.
    private const val CMSG_LEN_OFFSET = 0L
    private const val CMSG_LEVEL_OFFSET = 4L
    private const val CMSG_TYPE_OFFSET = 8L
    private const val CMSG_DATA_OFFSET = 12L
    private const val CMSG_HEADER = 12
    private const val FD_BYTES = 4

    /**
     * Room for more than the one expected (four descriptors in one message, or two messages of one), so extras arrive
     * whole and each can be closed. Truncated, the kernel would install the ones that fit and say only `MSG_CTRUNC`.
     */
    private const val CONTROL_BYTES = 32L

    private fun align(length: Int) = (length + FD_BYTES - 1) / FD_BYTES * FD_BYTES
}
