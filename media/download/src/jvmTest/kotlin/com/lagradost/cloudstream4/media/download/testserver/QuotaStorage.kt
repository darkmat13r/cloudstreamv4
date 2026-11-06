package com.lagradost.cloudstream4.media.download.testserver

import com.lagradost.cloudstream4.media.download.DownloadStorage
import com.lagradost.cloudstream4.media.download.FileKitStorage
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A [DownloadStorage] over [inner] whose disk fills up: once [limitBytes] have been written through
 * any sink, the write that crosses the line stores what still fits and throws an [IOException].
 *
 * [advertisedAvailable] is what [availableBytes] reports. By default that is what is left of the
 * quota, which is honest; pass a function to make the disk lie ("plenty of room", `null`, or "none").
 * Bytes are counted cumulatively: deleting a part file does not give quota back.
 */
class QuotaStorage(
    private val inner: DownloadStorage = FileKitStorage(),
    private val limitBytes: Long = Long.MAX_VALUE,
    private val advertisedAvailable: ((written: Long) -> Long?)? = null,
    private val onWriteFailure: () -> Unit = {},
    private val errorMessage: String = "No space left on device",
) : DownloadStorage by inner {
    val written = AtomicLong()
    val failedWrites = AtomicInteger()
    val sinksOpened = AtomicInteger()

    override fun sink(file: PlatformFile, append: Boolean): RawSink {
        sinksOpened.incrementAndGet()
        return QuotaSink(inner.sink(file, append))
    }

    override suspend fun availableBytes(directory: PlatformFile): Long? {
        advertisedAvailable?.let { return it(written.get()) }
        return if (limitBytes == Long.MAX_VALUE) inner.availableBytes(directory) else (limitBytes - written.get()).coerceAtLeast(0L)
    }

    private inner class QuotaSink(private val delegate: RawSink) : RawSink {
        override fun write(source: Buffer, byteCount: Long) {
            val room = limitBytes - written.get()
            if (byteCount <= room) {
                delegate.write(source, byteCount)
                written.addAndGet(byteCount)
                return
            }
            if (room > 0) {
                delegate.write(source, room)
                written.addAndGet(room)
            }
            failedWrites.incrementAndGet()
            onWriteFailure()
            throw IOException(errorMessage)
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }
}
