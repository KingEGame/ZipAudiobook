package com.kingwar.zipbook.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * End-to-end tests over real ZIP bytes written to disk.
 *
 * The archives are produced by a minimal writer in this test file rather than by
 * [java.util.zip.ZipOutputStream] because the point is to control the parts
 * ZipOutputStream hides: the name charset and the UTF-8 general-purpose flag. That is
 * exactly the condition a Russian audiobook rip arrives in.
 */
class ZipReaderEndToEndTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // --- minimal ZIP writer -------------------------------------------------

    private class Zip(val charset: Charset, val setUtf8Flag: Boolean) {
        private val out = ByteArrayOutputStream()
        private val central = ByteArrayOutputStream()
        private var count = 0

        fun add(name: String, data: ByteArray, deflate: Boolean = true) {
            val nameBytes = name.toByteArray(charset)
            val crc = CRC32().apply { update(data) }.value
            val payload = if (deflate) deflateRaw(data) else data
            val method = if (deflate) 8 else 0
            val flags = if (setUtf8Flag) 0x0800 else 0
            val localOffset = out.size()

            out.writeIntLE(0x04034b50)
            out.writeShortLE(20)
            out.writeShortLE(flags)
            out.writeShortLE(method)
            out.writeShortLE(0); out.writeShortLE(0)      // time, date
            out.writeIntLE(crc.toInt())
            out.writeIntLE(payload.size)
            out.writeIntLE(data.size)
            out.writeShortLE(nameBytes.size)
            out.writeShortLE(0)                            // extra len
            out.write(nameBytes)
            out.write(payload)

            central.writeIntLE(0x02014b50)
            central.writeShortLE(20); central.writeShortLE(20)
            central.writeShortLE(flags)
            central.writeShortLE(method)
            central.writeShortLE(0); central.writeShortLE(0)
            central.writeIntLE(crc.toInt())
            central.writeIntLE(payload.size)
            central.writeIntLE(data.size)
            central.writeShortLE(nameBytes.size)
            central.writeShortLE(0); central.writeShortLE(0)
            central.writeShortLE(0); central.writeShortLE(0)
            central.writeIntLE(0)
            central.writeIntLE(localOffset)
            central.write(nameBytes)
            count++
        }

        fun finish(file: File) {
            val cdOffset = out.size()
            val cd = central.toByteArray()
            out.write(cd)
            out.writeIntLE(0x06054b50)
            out.writeShortLE(0); out.writeShortLE(0)
            out.writeShortLE(count); out.writeShortLE(count)
            out.writeIntLE(cd.size)
            out.writeIntLE(cdOffset)
            out.writeShortLE(0)
            file.writeBytes(out.toByteArray())
        }

        private fun deflateRaw(data: ByteArray): ByteArray {
            val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            d.setInput(data); d.finish()
            val buf = ByteArray(data.size + 256)
            val n = d.deflate(buf)
            d.end()
            return buf.copyOf(n)
        }

        private fun ByteArrayOutputStream.writeShortLE(v: Int) {
            write(v and 0xFF); write((v ushr 8) and 0xFF)
        }

        private fun ByteArrayOutputStream.writeIntLE(v: Int) {
            write(v and 0xFF); write((v ushr 8) and 0xFF)
            write((v ushr 16) and 0xFF); write((v ushr 24) and 0xFF)
        }
    }

    private fun payload(seed: Int, size: Int = 2048): ByteArray =
        ByteArray(size) { ((it * 31 + seed) and 0xFF).toByte() }

    // --- tests --------------------------------------------------------------

    @Test
    fun `flat utf8 archive with mixed padding reads back in numeric order`() {
        val file = tmp.newFile("book.zip")
        Zip(Charsets.UTF_8, setUtf8Flag = true).apply {
            // Written deliberately out of order.
            add("0010.mp3", payload(10))
            add("00.mp3", payload(0))
            add("0002.mp3", payload(2))
            add("0001.mp3", payload(1))
            add("cover.jpg", payload(99))
        }.finish(file)

        val lib = ZipReader.readLibrary(file)
        assertEquals(Layout.FLAT_SINGLE_BOOK, lib.layout)
        assertEquals(1, lib.books.size)
        assertEquals(
            listOf("00.mp3", "0001.mp3", "0002.mp3", "0010.mp3"),
            lib.books[0].chapters.map { it.entry.fileName },
        )
        // The image is now claimed as the book's cover rather than skipped.
        assertEquals("cover.jpg", lib.books[0].coverEntryPath)
        assertTrue(lib.skipped.isEmpty())
    }

    @Test
    fun `cp866 named series archive is fully recovered`() {
        val file = tmp.newFile("series866.zip")
        Zip(charset("CP866"), setUtf8Flag = false).apply {
            add("Лесовик/Том 02/0001.mp3", payload(21))
            add("Лесовик/Том 02/0002.mp3", payload(22))
            add("Лесовик/Том 01/0002.mp3", payload(12))
            add("Лесовик/Том 01/0001.mp3", payload(11))
            add("Лесовик/Том 10/0001.mp3", payload(101))
        }.finish(file)

        val lib = ZipReader.readLibrary(file)
        assertEquals(Layout.SERIES_WITH_VOLUMES, lib.layout)
        assertEquals("Лесовик", lib.books[0].series)
        assertEquals(listOf(1, 2, 10), lib.books.map { it.seriesIndex })
        assertEquals(5, lib.chapterCount)
        // No mojibake survived anywhere in the tree.
        assertFalse(lib.books.any { it.sourceDirectory.contains('\uFFFD') })
        assertEquals("Лесовик/Том 01", lib.books[0].sourceDirectory)
    }

    @Test
    fun `windows-1251 named archive is recovered`() {
        val file = tmp.newFile("cp1251.zip")
        Zip(charset("windows-1251"), setUtf8Flag = false).apply {
            add("Книга/Глава 01.mp3", payload(1))
            add("Книга/Глава 02.mp3", payload(2))
        }.finish(file)

        val lib = ZipReader.readLibrary(file)
        assertEquals(1, lib.books.size)
        assertEquals("Книга", lib.books[0].title)
        assertEquals(listOf(1, 2), lib.books[0].chapters.map { it.number })
    }

    @Test
    fun `entry bytes are byte-identical after deflate round trip`() {
        val file = tmp.newFile("content.zip")
        val expected = payload(42, 4096)
        Zip(Charsets.UTF_8, setUtf8Flag = true).apply {
            add("0001.mp3", expected, deflate = true)
        }.finish(file)

        val raw = ZipReader.listEntries(file).single { it.name == "0001.mp3" }
        val actual = ZipReader.openEntry(file, raw).use { it.readBytes() }
        assertArrayEqualsMsg(expected, actual)
    }

    @Test
    fun `stored entries are reported as seekable and read back intact`() {
        val file = tmp.newFile("stored.zip")
        val expected = payload(7, 3000)
        Zip(Charsets.UTF_8, setUtf8Flag = true).apply {
            add("0001.mp3", expected, deflate = false)
        }.finish(file)

        val raw = ZipReader.listEntries(file).single()
        assertTrue("STORED entry must be flagged seekable", raw.stored)
        val actual = ZipReader.openEntry(file, raw).use { it.readBytes() }
        assertArrayEqualsMsg(expected, actual)

        val lib = ZipReader.readLibrary(file)
        assertTrue(lib.books[0].chapters[0].entry.stored)
    }

    @Test
    fun `sizes are carried through to the library model`() {
        val file = tmp.newFile("sizes.zip")
        Zip(Charsets.UTF_8, setUtf8Flag = true).apply {
            add("0001.mp3", payload(1, 5000))
            add("0002.mp3", payload(2, 7000))
        }.finish(file)

        val lib = ZipReader.readLibrary(file)
        assertEquals(12_000L, lib.books[0].totalBytes)
    }

    @Test
    fun `a non-zip file fails with a clear error`() {
        val file = tmp.newFile("not.zip")
        file.writeText("this is not a zip archive, not even close")
        try {
            ZipReader.listEntries(file)
            throw AssertionError("expected NotAZipException")
        } catch (e: ZipReader.NotAZipException) {
            assertTrue(e.message!!.contains("central-directory"))
        }
    }

    @Test
    fun `large entry count parses without truncation`() {
        val file = tmp.newFile("many.zip")
        val zip = Zip(Charsets.UTF_8, setUtf8Flag = true)
        for (i in 0 until 500) zip.add("%04d.mp3".format(i), payload(i, 64))
        zip.finish(file)

        val lib = ZipReader.readLibrary(file)
        assertEquals(500, lib.chapterCount)
        assertEquals("0000.mp3", lib.books[0].chapters.first().entry.fileName)
        assertEquals("0499.mp3", lib.books[0].chapters.last().entry.fileName)
    }

    private fun assertArrayEqualsMsg(expected: ByteArray, actual: ByteArray) {
        assertEquals("length", expected.size, actual.size)
        for (i in expected.indices) {
            if (expected[i] != actual[i]) {
                throw AssertionError("byte mismatch at $i: ${expected[i]} != ${actual[i]}")
            }
        }
    }
}
