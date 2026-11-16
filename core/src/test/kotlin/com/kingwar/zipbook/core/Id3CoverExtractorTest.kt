package com.kingwar.zipbook.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class Id3CoverExtractorTest {

    // --- real file, produced by ffmpeg with an attached picture ---------------

    @Test
    fun `cover is extracted from a real mp3 with an embedded picture`() {
        val stream = javaClass.getResourceAsStream("/track_with_embedded_cover.mp3")
        assertNotNull("fixture missing", stream)
        val cover = stream!!.use { Id3CoverExtractor.extract(it) }

        assertNotNull("no cover found in a file that definitely has one", cover)
        assertEquals("png", cover!!.extension)
        assertTrue("payload must be a real PNG", ImageSniffer.looksLikeImage(cover.bytes))
        // The embedded PNG was ~20 KB; allow slack but reject a truncated read.
        assertTrue("suspiciously small payload: ${cover.bytes.size}", cover.bytes.size > 15_000)
        assertEquals(
            "PNG magic",
            listOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()),
            cover.bytes.take(4),
        )
    }

    @Test
    fun `a real mp3 without a picture yields null`() {
        val stream = javaClass.getResourceAsStream("/track_without_cover.mp3")
        assertNotNull("fixture missing", stream)
        assertNull(stream!!.use { Id3CoverExtractor.extract(it) })
    }

    // --- synthetic tags ------------------------------------------------------

    @Test
    fun `id3v23 apic with latin1 description is parsed`() {
        val jpeg = fakeJpeg(512)
        val mp3 = id3v2(
            majorVersion = 3,
            frames = listOf(apicFrame(mime = "image/jpeg", type = 3, description = "Front", image = jpeg)),
        )
        val cover = Id3CoverExtractor.extract(ByteArrayInputStream(mp3))
        assertNotNull(cover)
        assertEquals("image/jpeg", cover!!.mimeType)
        assertEquals(3, cover.pictureType)
        assertEquals("jpg", cover.extension)
        assertEquals(jpeg.size, cover.bytes.size)
    }

    @Test
    fun `id3v24 synchsafe frame size is parsed`() {
        val png = fakePng(400)
        val mp3 = id3v2(
            majorVersion = 4,
            frames = listOf(apicFrame("image/png", 3, "Cover", png)),
            synchsafeFrameSizes = true,
        )
        val cover = Id3CoverExtractor.extract(ByteArrayInputStream(mp3))
        assertNotNull(cover)
        assertEquals("png", cover!!.extension)
        assertEquals(png.size, cover.bytes.size)
    }

    @Test
    fun `utf16 description is skipped correctly`() {
        val jpeg = fakeJpeg(256)
        // encoding 1 = UTF-16 with BOM, terminated by two zero bytes
        val payload = ByteArrayOutputStream().apply {
            write(1)
            write("image/jpeg".toByteArray(Charsets.ISO_8859_1)); write(0)
            write(3)
            write(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) // BOM
            write("Обложка".toByteArray(Charsets.UTF_16LE))
            write(byteArrayOf(0, 0))
            write(jpeg)
        }.toByteArray()
        val mp3 = id3v2(3, listOf("APIC" to payload))
        val cover = Id3CoverExtractor.extract(ByteArrayInputStream(mp3))
        assertNotNull("UTF-16 description broke the image offset", cover)
        assertEquals(jpeg.size, cover!!.bytes.size)
    }

    @Test
    fun `front cover is preferred over other picture types`() {
        val back = fakeJpeg(100)
        val front = fakePng(900)
        val mp3 = id3v2(
            3,
            listOf(
                apicFrame("image/jpeg", type = 4, description = "Back", image = back),
                apicFrame("image/png", type = 3, description = "Front", image = front),
            ),
        )
        val cover = Id3CoverExtractor.extract(ByteArrayInputStream(mp3))
        assertEquals(3, cover!!.pictureType)
        assertEquals(front.size, cover.bytes.size)
    }

    @Test
    fun `id3v22 PIC frame is parsed`() {
        val jpeg = fakeJpeg(300)
        val payload = ByteArrayOutputStream().apply {
            write(0)
            write("JPG".toByteArray(Charsets.ISO_8859_1))
            write(3)
            write("c".toByteArray(Charsets.ISO_8859_1)); write(0)
            write(jpeg)
        }.toByteArray()
        val mp3 = id3v22(listOf("PIC" to payload))
        val cover = Id3CoverExtractor.extract(ByteArrayInputStream(mp3))
        assertNotNull(cover)
        assertEquals("image/jpeg", cover!!.mimeType)
        assertEquals(jpeg.size, cover.bytes.size)
    }

    @Test
    fun `a payload that is not an image is rejected`() {
        val garbage = ByteArray(200) { 0x41 }
        val mp3 = id3v2(3, listOf(apicFrame("image/jpeg", 3, "x", garbage)))
        assertNull(Id3CoverExtractor.extract(ByteArrayInputStream(mp3)))
    }

    @Test
    fun `a file without an id3 header yields null`() {
        val notTagged = ByteArray(2048) { 0xFF.toByte() }
        assertNull(Id3CoverExtractor.extract(ByteArrayInputStream(notTagged)))
    }

    @Test
    fun `an empty stream yields null and does not throw`() {
        assertNull(Id3CoverExtractor.extract(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `a tag with only text frames yields null`() {
        val title = ByteArrayOutputStream().apply {
            write(0); write("Глава 1".toByteArray(Charsets.ISO_8859_1))
        }.toByteArray()
        val mp3 = id3v2(3, listOf("TIT2" to title))
        assertNull(Id3CoverExtractor.extract(ByteArrayInputStream(mp3)))
    }

    // --- builders ------------------------------------------------------------

    private fun apicFrame(
        mime: String,
        type: Int,
        description: String,
        image: ByteArray,
    ): Pair<String, ByteArray> {
        val payload = ByteArrayOutputStream().apply {
            write(0) // latin-1
            write(mime.toByteArray(Charsets.ISO_8859_1)); write(0)
            write(type)
            write(description.toByteArray(Charsets.ISO_8859_1)); write(0)
            write(image)
        }.toByteArray()
        return "APIC" to payload
    }

    /** ID3v2.3/2.4 tag followed by a dummy MPEG frame. */
    private fun id3v2(
        majorVersion: Int,
        frames: List<Pair<String, ByteArray>>,
        synchsafeFrameSizes: Boolean = majorVersion >= 4,
    ): ByteArray {
        val body = ByteArrayOutputStream()
        frames.forEach { (id, payload) ->
            body.write(id.toByteArray(Charsets.ISO_8859_1))
            body.write(if (synchsafeFrameSizes) toSynchsafe(payload.size) else toBeInt(payload.size))
            body.write(byteArrayOf(0, 0))
            body.write(payload)
        }
        val bodyBytes = body.toByteArray()

        return ByteArrayOutputStream().apply {
            write("ID3".toByteArray(Charsets.ISO_8859_1))
            write(majorVersion); write(0)
            write(0) // flags
            write(toSynchsafe(bodyBytes.size))
            write(bodyBytes)
            write(ByteArray(64) { 0xFF.toByte() }) // stand-in for audio data
        }.toByteArray()
    }

    private fun id3v22(frames: List<Pair<String, ByteArray>>): ByteArray {
        val body = ByteArrayOutputStream()
        frames.forEach { (id, payload) ->
            body.write(id.toByteArray(Charsets.ISO_8859_1))
            body.write(toBe24(payload.size))
            body.write(payload)
        }
        val bodyBytes = body.toByteArray()
        return ByteArrayOutputStream().apply {
            write("ID3".toByteArray(Charsets.ISO_8859_1))
            write(2); write(0)
            write(0)
            write(toSynchsafe(bodyBytes.size))
            write(bodyBytes)
            write(ByteArray(64) { 0xFF.toByte() })
        }.toByteArray()
    }

    private fun toSynchsafe(v: Int) = byteArrayOf(
        ((v shr 21) and 0x7F).toByte(),
        ((v shr 14) and 0x7F).toByte(),
        ((v shr 7) and 0x7F).toByte(),
        (v and 0x7F).toByte(),
    )

    private fun toBeInt(v: Int) = byteArrayOf(
        ((v shr 24) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    private fun toBe24(v: Int) = byteArrayOf(
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    private fun fakeJpeg(size: Int) = ByteArray(size).also {
        it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte()
    }

    private fun fakePng(size: Int) = ByteArray(size).also {
        it[0] = 0x89.toByte(); it[1] = 'P'.code.toByte()
        it[2] = 'N'.code.toByte(); it[3] = 'G'.code.toByte()
    }
}
