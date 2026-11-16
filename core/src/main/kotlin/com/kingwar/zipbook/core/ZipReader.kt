package com.kingwar.zipbook.core

import java.io.File
import java.io.InputStream

/**
 * Reads a ZIP central directory directly, without [java.util.zip.ZipFile].
 *
 * Why not ZipFile: it decodes entry names with a fixed charset chosen at construction
 * time, so an archive mixing UTF-8 and CP866 names (very common when a rip is repacked)
 * is unrecoverable. Parsing the central directory ourselves keeps the raw name bytes and
 * the general-purpose flags, which is exactly what [ZipNameDecoder] needs.
 *
 * It also records each entry's local-header offset and compression method, so one
 * chapter can be pulled out of the archive on demand instead of extracting a whole
 * book up front.
 */
object ZipReader {

    private const val EOCD_SIG = 0x06054b50
    private const val EOCD64_LOCATOR_SIG = 0x07064b50
    private const val EOCD64_SIG = 0x06064b50
    private const val CEN_SIG = 0x02014b50

    /** One central-directory record with everything playback and grouping need. */
    data class RawEntry(
        val name: String,
        val uncompressedSize: Long,
        val compressedSize: Long,
        val compressionMethod: Int,
        val localHeaderOffset: Long,
        val isDirectory: Boolean,
    ) {
        /** STORED entries carry their bytes verbatim, so they can be read at an offset. */
        val stored: Boolean get() = compressionMethod == 0
    }

    class NotAZipException(message: String) : Exception(message)

    // --- public API ---------------------------------------------------------

    fun listEntries(file: File): List<RawEntry> =
        ZipSource.of(file).use { listEntries(it) }

    fun audioEntries(file: File): List<AudioEntry> =
        ZipSource.of(file).use { audioEntries(it) }

    fun readLibrary(file: File): ArchiveLibrary =
        ZipSource.of(file).use { LibraryBuilder.build(file.name, audioEntries(it)) }

    fun openEntry(file: File, entry: RawEntry): InputStream {
        // The source must outlive the returned stream, so it is closed by the stream.
        val source = ZipSource.of(file)
        return openEntry(source, entry, closeSourceWithStream = true)
    }

    /** Reads the central directory and returns every entry with a recovered name. */
    fun listEntries(source: ZipSource): List<RawEntry> {
        val eocd = findEocd(source) ?: throw NotAZipException("no ZIP end-of-central-directory found")
        var cdOffset = eocd.cdOffset
        var cdEntries = eocd.entryCount

        if (cdOffset == 0xFFFFFFFFL || cdEntries == 0xFFFF) {
            findZip64(source, eocd.eocdOffset)?.let {
                cdOffset = it.cdOffset
                cdEntries = it.entryCount
            }
        }

        if (cdOffset < 0 || cdOffset >= source.size) {
            throw NotAZipException("central-directory offset $cdOffset outside archive")
        }

        val cdSize = (source.size - cdOffset).toInt()
        val cd = ByteArray(cdSize)
        source.readFully(cdOffset, cd)

        val entries = ArrayList<RawEntry>(cdEntries.coerceIn(0, 100_000))
        var p = 0
        while (p + 46 <= cd.size) {
            if (readInt(cd, p) != CEN_SIG) break
            val flags = readShort(cd, p + 8)
            val method = readShort(cd, p + 10)
            var compressed = readInt(cd, p + 20).toUInt().toLong()
            var uncompressed = readInt(cd, p + 24).toUInt().toLong()
            val nameLen = readShort(cd, p + 28)
            val extraLen = readShort(cd, p + 30)
            val commentLen = readShort(cd, p + 32)
            var localOffset = readInt(cd, p + 42).toUInt().toLong()

            if (p + 46 + nameLen > cd.size) break
            val nameBytes = cd.copyOfRange(p + 46, p + 46 + nameLen)
            val utf8 = (flags and 0x0800) != 0
            val name = ZipNameDecoder.decode(nameBytes, utf8).replace('\\', '/')

            // ZIP64 extended information field (0x0001) overrides the 32-bit values.
            val extraStart = p + 46 + nameLen
            if (extraLen > 0 && extraStart + extraLen <= cd.size) {
                var e = extraStart
                while (e + 4 <= extraStart + extraLen) {
                    val headerId = readShort(cd, e)
                    val dataSize = readShort(cd, e + 2)
                    if (headerId == 0x0001) {
                        var d = e + 4
                        if (uncompressed == 0xFFFFFFFFL && d + 8 <= cd.size) {
                            uncompressed = readLong(cd, d); d += 8
                        }
                        if (compressed == 0xFFFFFFFFL && d + 8 <= cd.size) {
                            compressed = readLong(cd, d); d += 8
                        }
                        if (localOffset == 0xFFFFFFFFL && d + 8 <= cd.size) {
                            localOffset = readLong(cd, d)
                        }
                        break
                    }
                    e += 4 + dataSize
                }
            }

            entries += RawEntry(
                name = name,
                uncompressedSize = uncompressed,
                compressedSize = compressed,
                compressionMethod = method,
                localHeaderOffset = localOffset,
                isDirectory = name.endsWith("/"),
            )
            p = extraStart + extraLen + commentLen
        }
        return entries
    }

    /** Entries converted to the grouping model, directories and empties dropped. */
    fun audioEntries(source: ZipSource): List<AudioEntry> =
        listEntries(source)
            .filterNot { it.isDirectory }
            .map {
                AudioEntry(
                    path = it.name.trimStart('/'),
                    sizeBytes = it.uncompressedSize,
                    compressedSizeBytes = it.compressedSize,
                    stored = it.stored,
                )
            }

    fun readLibrary(archiveName: String, source: ZipSource): ArchiveLibrary =
        LibraryBuilder.build(archiveName, audioEntries(source))

    /**
     * Opens one entry for reading. STORED entries stream straight out of the archive;
     * DEFLATED entries are inflated on the fly.
     */
    fun openEntry(
        source: ZipSource,
        entry: RawEntry,
        closeSourceWithStream: Boolean = false,
    ): InputStream {
        val header = ByteArray(30)
        source.readFully(entry.localHeaderOffset, header)
        val nameLen = readShort(header, 26)
        val extraLen = readShort(header, 28)
        val dataStart = entry.localHeaderOffset + 30 + nameLen + extraLen
        val raw = source.openRange(dataStart, entry.compressedSize)
        val body = if (entry.stored) {
            raw
        } else {
            java.util.zip.InflaterInputStream(raw, java.util.zip.Inflater(true), 64 * 1024)
        }
        return if (closeSourceWithStream) ClosingStream(body, source) else body
    }

    private class ClosingStream(
        private val delegate: InputStream,
        private val source: ZipSource,
    ) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun available(): Int = delegate.available()
        override fun close() {
            try {
                delegate.close()
            } finally {
                source.close()
            }
        }
    }

    // --- central directory location ----------------------------------------

    private data class Eocd(val entryCount: Int, val cdOffset: Long, val eocdOffset: Long)

    private fun findEocd(source: ZipSource): Eocd? {
        val len = source.size
        if (len < 22) return null
        val maxBack = minOf(len, 64L * 1024 + 22)
        val buf = ByteArray(maxBack.toInt())
        source.readFully(len - maxBack, buf)
        var i = buf.size - 22
        while (i >= 0) {
            if (readInt(buf, i) == EOCD_SIG) {
                val count = readShort(buf, i + 10)
                val offset = readInt(buf, i + 16).toUInt().toLong()
                return Eocd(count, offset, len - maxBack + i)
            }
            i--
        }
        return null
    }

    private data class Zip64(val entryCount: Int, val cdOffset: Long)

    private fun findZip64(source: ZipSource, eocdOffset: Long): Zip64? {
        val locatorOffset = eocdOffset - 20
        if (locatorOffset < 0) return null
        val loc = ByteArray(20)
        source.readFully(locatorOffset, loc)
        if (readInt(loc, 0) != EOCD64_LOCATOR_SIG) return null
        val z64Offset = readLong(loc, 8)
        if (z64Offset < 0 || z64Offset + 56 > source.size) return null
        val rec = ByteArray(56)
        source.readFully(z64Offset, rec)
        if (readInt(rec, 0) != EOCD64_SIG) return null
        return Zip64(readLong(rec, 32).toInt(), readLong(rec, 48))
    }

    // --- little-endian primitives ------------------------------------------

    private fun readShort(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun readInt(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun readLong(b: ByteArray, o: Int): Long {
        var v = 0L
        for (k in 7 downTo 0) {
            v = (v shl 8) or (b[o + k].toLong() and 0xFF)
        }
        return v
    }
}
