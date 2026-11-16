package com.kingwar.zipbook.core

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Random-access byte source for ZIP parsing.
 *
 * Exists so the archive can be read in place from wherever Android hands it over.
 * A document picked through the Storage Access Framework arrives as a `Uri`; its
 * `ParcelFileDescriptor` yields a seekable [FileChannel], so the central directory can
 * be parsed and single chapters extracted without first duplicating a multi-gigabyte
 * archive into app storage.
 */
interface ZipSource : Closeable {
    val size: Long

    /** Reads exactly [length] bytes at [position] or throws. */
    fun readFully(position: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size)

    /** Opens a bounded stream over [length] bytes starting at [position]. */
    fun openRange(position: Long, length: Long): InputStream

    companion object {
        fun of(file: File): ZipSource = FileZipSource(file)

        /**
         * Wraps a channel. The caller keeps ownership of the underlying descriptor;
         * [close] on the returned source closes the channel only.
         */
        fun of(channel: FileChannel): ZipSource = ChannelZipSource(channel)
    }
}

private class FileZipSource(file: File) : ZipSource {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()

    override fun readFully(position: Long, buffer: ByteArray, offset: Int, length: Int) {
        synchronized(raf) {
            raf.seek(position)
            raf.readFully(buffer, offset, length)
        }
    }

    override fun openRange(position: Long, length: Long): InputStream =
        object : InputStream() {
            private var pos = position
            private var remaining = length

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val toRead = minOf(len.toLong(), remaining).toInt()
                val n = synchronized(raf) {
                    raf.seek(pos)
                    raf.read(b, off, toRead)
                }
                if (n > 0) {
                    pos += n
                    remaining -= n
                }
                return n
            }
        }

    override fun close() = raf.close()
}

private class ChannelZipSource(private val channel: FileChannel) : ZipSource {
    override val size: Long = channel.size()

    override fun readFully(position: Long, buffer: ByteArray, offset: Int, length: Int) {
        val buf = ByteBuffer.wrap(buffer, offset, length)
        var p = position
        while (buf.hasRemaining()) {
            val n = channel.read(buf, p)
            if (n <= 0) throw java.io.EOFException("short read at $p")
            p += n
        }
    }

    override fun openRange(position: Long, length: Long): InputStream =
        object : InputStream() {
            private var pos = position
            private var remaining = length

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val toRead = minOf(len.toLong(), remaining).toInt()
                val n = channel.read(ByteBuffer.wrap(b, off, toRead), pos)
                if (n > 0) {
                    pos += n
                    remaining -= n
                }
                return n
            }
        }

    override fun close() = channel.close()
}
