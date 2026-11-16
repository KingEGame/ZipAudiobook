package com.kingwar.zipbook.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class Id3TagReaderTest {

    @Test
    fun `id3v23 latin1 text frames are read`() {
        val mp3 = id3v2(
            majorVersion = 3,
            frames = listOf(
                textFrame("TIT2", 0, "Chapter 1. Prologue"),
                textFrame("TPE1", 0, "John Smith"),
                textFrame("TALB", 0, "Book One"),
            ),
        )
        val tags = Id3TagReader.extract(ByteArrayInputStream(mp3))
        assertEquals("Chapter 1. Prologue", tags?.title)
        assertEquals("John Smith", tags?.artist)
        assertEquals("Book One", tags?.album)
    }

    @Test
    fun `id3v24 utf16 title with BOM is decoded`() {
        val mp3 = id3v2(
            majorVersion = 4,
            frames = listOf(textFrame("TIT2", 1, "Пролог: начало пути")),
        )
        val tags = Id3TagReader.extract(ByteArrayInputStream(mp3))
        assertEquals("Пролог: начало пути", tags?.title)
    }

    @Test
    fun `id3v22 short frame ids are read`() {
        val mp3 = id3v22(
            frames = listOf(
                textFrame("TT2", 0, "Chapter Three"),
                textFrame("TP1", 0, "Author"),
            ),
        )
        val tags = Id3TagReader.extract(ByteArrayInputStream(mp3))
        assertEquals("Chapter Three", tags?.title)
        assertEquals("Author", tags?.artist)
    }

    @Test
    fun `a tag with no text frames yields null`() {
        val mp3 = id3v2(majorVersion = 3, frames = listOf(apicFrame(fakeJpeg(32))))
        assertNull(Id3TagReader.extract(ByteArrayInputStream(mp3)))
    }

    @Test
    fun `no ID3 header at all yields null`() {
        assertNull(Id3TagReader.extract(ByteArrayInputStream(ByteArray(32))))
    }

    @Test
    fun `multi-valued v24 frame keeps only the first value`() {
        val payload = ByteArrayOutputStream().apply {
            write(3) // utf-8
            write("Главный автор".toByteArray(Charsets.UTF_8)); write(0)
            write("Соавтор".toByteArray(Charsets.UTF_8))
        }.toByteArray()
        val mp3 = id3v2(majorVersion = 4, frames = listOf("TPE1" to payload))
        val tags = Id3TagReader.extract(ByteArrayInputStream(mp3))
        assertEquals("Главный автор", tags?.artist)
    }

    // --- builders --------------------------------------------------------------

    private fun textFrame(id: String, encoding: Int, text: String): Pair<String, ByteArray> {
        val payload = ByteArrayOutputStream().apply {
            write(encoding)
            when (encoding) {
                1 -> write(text.toByteArray(Charsets.UTF_16)) // includes BOM
                2 -> write(text.toByteArray(Charsets.UTF_16BE))
                3 -> write(text.toByteArray(Charsets.UTF_8))
                else -> write(text.toByteArray(Charsets.ISO_8859_1))
            }
        }.toByteArray()
        return id to payload
    }

    private fun apicFrame(image: ByteArray): Pair<String, ByteArray> {
        val payload = ByteArrayOutputStream().apply {
            write(0)
            write("image/jpeg".toByteArray(Charsets.ISO_8859_1)); write(0)
            write(3)
            write(0)
            write(image)
        }.toByteArray()
        return "APIC" to payload
    }

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
            write(0)
            write(toSynchsafe(bodyBytes.size))
            write(bodyBytes)
            write(ByteArray(64) { 0xFF.toByte() })
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
}
