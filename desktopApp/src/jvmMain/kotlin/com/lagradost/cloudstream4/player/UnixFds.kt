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
    fun send(socket: Int, passed: Int): Boolean {
        val message = Message()
        message.control.setInt(CMSG_LEN_OFFSET, CMSG_LENGTH)
        message.control.setInt(CMSG_LEVEL_OFFSET, SOL_SOCKET)
        message.control.setInt(CMSG_TYPE_OFFSET, SCM_RIGHTS)
        message.control.setInt(CMSG_DATA_OFFSET, passed)
        return c.sendmsg(socket, message.header, 0) == 1L
    }

    /**
     * Receives what [send] sent: exactly one descriptor, with its marker byte, or null. Reads one byte only, and must be
     * the first read of [socket]: a plain read of that byte would drop the descriptor with it.
     */
    fun receive(socket: Int): Int? {
        val message = Message()
        if (c.recvmsg(socket, message.header, 0) != 1L) return null
        if (message.header.getInt(MSG_FLAGS_OFFSET) and MSG_CTRUNC != 0) return null
        val length = message.header.getInt(MSG_CONTROLLEN_OFFSET)
        if (length < CMSG_LENGTH) return null
        if (message.control.getInt(CMSG_LEVEL_OFFSET) != SOL_SOCKET) return null
        if (message.control.getInt(CMSG_TYPE_OFFSET) != SCM_RIGHTS) return null
        // One descriptor and no more: a second would be a descriptor nothing here closes.
        if (message.control.getInt(CMSG_LEN_OFFSET) != CMSG_LENGTH) return null
        return message.control.getInt(CMSG_DATA_OFFSET).takeIf { it >= 0 }
    }

    /** A `msghdr` with one byte of data and room for one `cmsghdr` carrying one descriptor. */
    private class Message {
        val data = Memory(1).apply { setByte(0, MARKER) }
        val vector = Memory(IOVEC_SIZE).apply {
            setPointer(0, data)
            setLong(Native.POINTER_SIZE.toLong(), 1)
        }
        val control = Memory(CMSG_SPACE).apply { clear() }
        val header = Memory(MSGHDR_SIZE).apply {
            clear()
            setPointer(MSG_IOV_OFFSET, vector)
            setInt(MSG_IOVLEN_OFFSET, 1)
            setPointer(MSG_CONTROL_OFFSET, control)
            setInt(MSG_CONTROLLEN_OFFSET, CMSG_SPACE.toInt())
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
    private const val CMSG_LENGTH = 16
    private const val CMSG_SPACE = 16L
}
