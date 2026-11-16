package com.kingwar.zipbook.core

import java.io.InputStream

/** An image recovered from an audio file's metadata. */
data class EmbeddedCover(
    val bytes: ByteArray,
    val mimeType: String,
    /** ID3 picture type; 3 means "front cover". */
    val pictureType: Int,
) {
    /** File extension implied by the actual image bytes, not by the declared MIME. */
    val extension: String get() = ImageSniffer.extensionOf(bytes) ?: when {
        mimeType.contains("png", ignoreCase = true) -> "png"
        mimeType.contains("webp", ignoreCase = true) -> "webp"
        else -> "jpg"
    }

    override fun equals(other: Any?): Boolean =
        other is EmbeddedCover && bytes.contentEquals(other.bytes) &&
            mimeType == other.mimeType && pictureType == other.pictureType

    override fun hashCode(): Int =
        bytes.contentHashCode() * 31 + mimeType.hashCode() * 31 + pictureType
}

/** Identifies image formats from their leading bytes. */
object ImageSniffer {
    fun extensionOf(b: ByteArray): String? = when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "jpg"
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() &&
            b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "png"
        b.size >= 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
            String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp"
        b.size >= 6 && String(b, 0, 6, Charsets.ISO_8859_1).startsWith("GIF8") -> "gif"
        else -> null
    }

    fun looksLikeImage(b: ByteArray): Boolean = extensionOf(b) != null
}

/**
 * Extracts cover art embedded in an MP3's ID3v2 tag (the `APIC` frame, or `PIC` in the
 * older ID3v2.2 layout).
 *
 * Needed because plenty of audiobook archives carry no separate `cover.jpg` — the art
 * lives inside the first track, which is where every desktop player finds it.
 *
 * Only the tag at the start of the file is read, and only up to [MAX_TAG_BYTES], so this
 * stays cheap even when pointed at a large chapter.
 */
object Id3CoverExtractor {

    private const val MAX_TAG_BYTES = 8 * 1024 * 1024

    /** Reads the ID3 tag from [input] and returns the best picture, if any. */
    fun extract(input: InputStream): EmbeddedCover? {
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

        // An extended header, when present, sits before the first frame.
        var p = if (flags and 0x40 != 0) {
            val extSize = if (majorVersion >= 4) synchsafe(body, 0) else beInt(body, 0) + 4
            extSize.coerceIn(0, body.size)
        } else {
            0
        }

        val covers = mutableListOf<EmbeddedCover>()

        if (majorVersion <= 2) {
            // ID3v2.2: 3-byte frame id, 3-byte size.
            while (p + 6 <= body.size) {
                val id = String(body, p, 3, Charsets.ISO_8859_1)
                if (id[0] == '\u0000') break
                val size = be24(body, p + 3)
                if (size <= 0 || p + 6 + size > body.size) break
                if (id == "PIC") {
                    parsePicV2(body, p + 6, size)?.let { covers += it }
                }
                p += 6 + size
            }
        } else {
            // ID3v2.3 / v2.4: 4-byte id, 4-byte size, 2-byte flags.
            while (p + 10 <= body.size) {
                val id = String(body, p, 4, Charsets.ISO_8859_1)
                if (id[0] == '\u0000') break
                // v2.4 sizes are synchsafe; v2.3 are plain big-endian. Some v2.4 writers
                // get this wrong, so a size that overruns the tag is retried the other way.
                var size = if (majorVersion >= 4) synchsafe(body, p + 4) else beInt(body, p + 4)
                if (size <= 0 || p + 10 + size > body.size) {
                    val alt = if (majorVersion >= 4) beInt(body, p + 4) else synchsafe(body, p + 4)
                    if (alt > 0 && p + 10 + alt <= body.size) size = alt else break
                }
                if (id == "APIC") {
                    parseApic(body, p + 10, size)?.let { covers += it }
                }
                p += 10 + size
            }
        }

        if (covers.isEmpty()) return null
        // Prefer the declared front cover (type 3), then any other picture.
        return covers.minByOrNull { if (it.pictureType == 3) 0 else 1 }
    }

    /** APIC: encoding(1) | mime\0 | type(1) | description(term) | image bytes */
    private fun parseApic(b: ByteArray, start: Int, size: Int): EmbeddedCover? {
        var p = start
        val end = start + size
        if (p >= end) return null
        val encoding = b[p].toInt() and 0xFF
        p++

        val mimeEnd = indexOfZero(b, p, end) ?: return null
        val mime = String(b, p, mimeEnd - p, Charsets.ISO_8859_1)
        p = mimeEnd + 1
        if (p >= end) return null

        val pictureType = b[p].toInt() and 0xFF
        p++

        p = skipTerminatedText(b, p, end, encoding) ?: return null
        if (p >= end) return null

        val bytes = b.copyOfRange(p, end)
        return if (ImageSniffer.looksLikeImage(bytes)) EmbeddedCover(bytes, mime, pictureType) else null
    }

    /** PIC (v2.2): encoding(1) | 3-char format | type(1) | description(term) | image bytes */
    private fun parsePicV2(b: ByteArray, start: Int, size: Int): EmbeddedCover? {
        var p = start
        val end = start + size
        if (p + 5 > end) return null
        val encoding = b[p].toInt() and 0xFF
        p++
        val format = String(b, p, 3, Charsets.ISO_8859_1)
        p += 3
        val pictureType = b[p].toInt() and 0xFF
        p++
        p = skipTerminatedText(b, p, end, encoding) ?: return null
        if (p >= end) return null
        val bytes = b.copyOfRange(p, end)
        val mime = if (format.equals("PNG", true)) "image/png" else "image/jpeg"
        return if (ImageSniffer.looksLikeImage(bytes)) EmbeddedCover(bytes, mime, pictureType) else null
    }

    /**
     * Skips a description string. UTF-16 encodings (1 and 2) terminate with two zero
     * bytes on an even boundary; latin-1 and UTF-8 use a single zero.
     */
    private fun skipTerminatedText(b: ByteArray, from: Int, end: Int, encoding: Int): Int? {
        if (encoding == 1 || encoding == 2) {
            var p = from
            while (p + 1 < end) {
                if (b[p] == 0.toByte() && b[p + 1] == 0.toByte()) return p + 2
                p += 2
            }
            return null
        }
        val zero = indexOfZero(b, from, end) ?: return null
        return zero + 1
    }

    private fun indexOfZero(b: ByteArray, from: Int, end: Int): Int? {
        var p = from
        while (p < end) {
            if (b[p] == 0.toByte()) return p
            p++
        }
        return null
    }

    /** ID3 synchsafe integer: four bytes, seven significant bits each. */
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
