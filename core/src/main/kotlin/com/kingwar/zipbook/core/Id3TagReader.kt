package com.kingwar.zipbook.core

import java.io.InputStream

/** Text metadata recovered from an ID3v2 tag. */
data class Id3Tags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
)

/**
 * Reads the TIT2 (title), TPE1 (artist) and TALB (album) text frames from an MP3's
 * ID3v2 tag — v2.2's 3-char "TT2"/"TP1"/"TAL", or v2.3/v2.4's 4-char "TIT2"/"TPE1"/"TALB".
 *
 * Needed because filenames inside audiobook archives are frequently just a running
 * number ("0001.mp3") and directory names carry no title either; the real chapter or
 * book name often lives only in the tag that a desktop player would show.
 */
object Id3TagReader {

    private const val MAX_TAG_BYTES = 8 * 1024 * 1024

    fun extract(input: InputStream): Id3Tags? {
        val header = ByteArray(10)
        if (!input.readFullyOrNull(header)) return null
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
            return null
        }

        val majorVersion = header[3].toInt() and 0xFF
        val flags = header[4].toInt() and 0xFF
        val tagSize = synchsafe(header, 6)
        if (tagSize <= 0 || tagSize > MAX_TAG_BYTES) return null

        val body = ByteArray(tagSize)
        if (!input.readFullyOrNull(body)) return null

        var p = if (flags and 0x40 != 0) {
            val extSize = if (majorVersion >= 4) synchsafe(body, 0) else beInt(body, 0) + 4
            extSize.coerceIn(0, body.size)
        } else {
            0
        }

        var title: String? = null
        var artist: String? = null
        var album: String? = null

        if (majorVersion <= 2) {
            // ID3v2.2: 3-byte frame id, 3-byte size.
            while (p + 6 <= body.size) {
                val id = String(body, p, 3, Charsets.ISO_8859_1)
                if (id[0] == '\u0000') break
                val size = be24(body, p + 3)
                if (size <= 0 || p + 6 + size > body.size) break
                if (id == "TT2" || id == "TP1" || id == "TAL") {
                    val text = decodeTextFrame(body, p + 6, size)
                    when (id) {
                        "TT2" -> title = text
                        "TP1" -> artist = text
                        "TAL" -> album = text
                    }
                }
                p += 6 + size
            }
        } else {
            // ID3v2.3 / v2.4: 4-byte id, 4-byte size, 2-byte flags.
            while (p + 10 <= body.size) {
                val id = String(body, p, 4, Charsets.ISO_8859_1)
                if (id[0] == '\u0000') break
                var size = if (majorVersion >= 4) synchsafe(body, p + 4) else beInt(body, p + 4)
                if (size <= 0 || p + 10 + size > body.size) {
                    val alt = if (majorVersion >= 4) beInt(body, p + 4) else synchsafe(body, p + 4)
                    if (alt > 0 && p + 10 + alt <= body.size) size = alt else break
                }
                if (id == "TIT2" || id == "TPE1" || id == "TALB") {
                    val text = decodeTextFrame(body, p + 10, size)
                    when (id) {
                        "TIT2" -> title = text
                        "TPE1" -> artist = text
                        "TALB" -> album = text
                    }
                }
                p += 10 + size
            }
        }

        if (title == null && artist == null && album == null) return null
        return Id3Tags(title, artist, album)
    }

    /** Text frame: encoding(1) | text, possibly null-terminated / multi-valued. */
    private fun decodeTextFrame(b: ByteArray, start: Int, size: Int): String? {
        if (size <= 1) return null
        val encoding = b[start].toInt() and 0xFF
        val textStart = start + 1
        val textEnd = start + size

        val raw = when (encoding) {
            1 -> {
                // UTF-16 with BOM: drop a leading BOM, Charsets.UTF_16 honours it either way.
                val trimmedEnd = trimUtf16(b, textStart, textEnd)
                String(b, textStart, (trimmedEnd - textStart).coerceAtLeast(0), Charsets.UTF_16)
            }
            2 -> {
                val trimmedEnd = trimUtf16(b, textStart, textEnd)
                String(b, textStart, (trimmedEnd - textStart).coerceAtLeast(0), Charsets.UTF_16BE)
            }
            3 -> {
                val trimmedEnd = trimTrailingZeros(b, textStart, textEnd)
                String(b, textStart, (trimmedEnd - textStart).coerceAtLeast(0), Charsets.UTF_8)
            }
            else -> {
                val trimmedEnd = trimTrailingZeros(b, textStart, textEnd)
                String(b, textStart, (trimmedEnd - textStart).coerceAtLeast(0), Charsets.ISO_8859_1)
            }
        }
        // v2.4 allows multiple null-separated values in one frame; the first is enough.
        return raw.substringBefore('\u0000').trim().ifBlank { null }
    }

    private fun trimUtf16(b: ByteArray, from: Int, end: Int): Int {
        var p = from
        while (p + 1 < end) {
            if (b[p] == 0.toByte() && b[p + 1] == 0.toByte()) return p
            p += 2
        }
        return end
    }

    private fun trimTrailingZeros(b: ByteArray, from: Int, end: Int): Int {
        var p = end
        while (p > from && b[p - 1] == 0.toByte()) p--
        return p
    }

    private fun synchsafe(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0x7F) shl 21) or
            ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or
            (b[o + 3].toInt() and 0x7F)

    private fun beInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or
            ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or
            (b[o + 3].toInt() and 0xFF)

    private fun be24(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 16) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            (b[o + 2].toInt() and 0xFF)
}

private fun InputStream.readFullyOrNull(buffer: ByteArray): Boolean {
    var read = 0
    while (read < buffer.size) {
        val n = read(buffer, read, buffer.size - read)
        if (n < 0) return false
        read += n
    }
    return true
}
