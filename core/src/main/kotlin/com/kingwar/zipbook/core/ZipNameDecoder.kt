package com.kingwar.zipbook.core

import java.nio.charset.Charset

/**
 * Recovers readable entry names from ZIP archives whose filenames are not UTF-8.
 *
 * Background: a ZIP entry carries a "language encoding" general-purpose bit (bit 11).
 * When set, names are UTF-8. When clear, the spec says names are IBM Code Page 437,
 * but in practice archivers on Windows write names in the OEM/ANSI codepage of the
 * machine that created the archive. Russian audiobook rips are therefore commonly
 * CP866 (DOS/OEM) or windows-1251 (ANSI), and reading them as UTF-8 or CP437 yields
 * mojibake like "Ðâ€ÐµÐ½ÑŒ" or "ÇÓ╠╬╩".
 *
 * Strategy: when the UTF-8 flag is absent, take the raw name bytes, decode them with
 * each candidate charset, and score the results. The highest-scoring candidate wins.
 * Scoring rewards coherent Cyrillic/Latin text and punishes box-drawing glyphs and
 * replacement characters, which are the signature of a wrong codepage.
 */
object ZipNameDecoder {

    /** Charsets tried for non-UTF-8 entry names, in descending real-world frequency. */
    private val candidateNames = listOf("CP866", "windows-1251", "IBM437", "KOI8-R")

    private val candidates: List<Charset> = candidateNames.mapNotNull { name ->
        runCatching { Charset.forName(name) }.getOrNull()
    }

    /**
     * Decodes a raw entry name.
     *
     * @param raw the entry name bytes exactly as stored in the archive
     * @param utf8Flag true when general-purpose bit 11 is set
     */
    fun decode(raw: ByteArray, utf8Flag: Boolean): String {
        if (utf8Flag) return String(raw, Charsets.UTF_8)
        // An archive may be UTF-8 without setting the flag (common with Info-ZIP on
        // Linux), so UTF-8 stays in the running and wins when it decodes cleanly.
        val strictUtf8 = decodeStrictUtf8(raw)
        var best: String? = null
        var bestScore = Int.MIN_VALUE
        if (strictUtf8 != null) {
            best = strictUtf8
            bestScore = score(strictUtf8) + UTF8_BONUS
        }
        for (cs in candidates) {
            val decoded = String(raw, cs)
            val s = score(decoded)
            if (s > bestScore) {
                bestScore = s
                best = decoded
            }
        }
        return best ?: String(raw, Charsets.ISO_8859_1)
    }

    /**
     * Repairs a name that was already decoded with the wrong charset by the platform
     * (the usual case when a library hands back a mojibake [String] and the original
     * bytes are gone). Round-trips through ISO-8859-1 to recover the bytes.
     */
    fun repairMojibake(decoded: String): String {
        if (!looksBroken(decoded)) return decoded
        val raw = decoded.toByteArray(Charsets.ISO_8859_1)
        return decode(raw, utf8Flag = false)
    }

    private fun decodeStrictUtf8(raw: ByteArray): String? {
        val decoder = Charsets.UTF_8.newDecoder()
        return runCatching {
            decoder.decode(java.nio.ByteBuffer.wrap(raw)).toString()
        }.getOrNull()
    }

    private fun looksBroken(s: String): Boolean =
        s.any { it == '\uFFFD' || it in BOX_DRAWING || it in '\u0080'..'\u009F' }

    private fun score(s: String): Int {
        var score = 0
        var cyrillic = 0
        for (ch in s) {
            score += when {
                ch == '\uFFFD' -> -40
                ch in BOX_DRAWING -> -25
                ch in '\u0080'..'\u009F' -> -20
                ch.isCyrillic() -> { cyrillic++; 6 }
                ch.isLetterOrDigit() && ch.code < 128 -> 4
                ch == ' ' || ch == '.' || ch == '-' || ch == '_' ||
                    ch == '(' || ch == ')' || ch == '/' || ch == '\'' ||
                    ch == ',' || ch == '!' || ch == '#' || ch == '&' -> 2
                ch.isLetter() -> 1
                else -> -6
            }
        }
        // Mixed-script noise (a few stray Cyrillic letters inside Latin garbage) is a
        // wrong-codepage tell, so only reward Cyrillic once it dominates.
        if (cyrillic in 1..2 && s.length > 12) score -= 10
        return score
    }

    private const val UTF8_BONUS = 8
    private val BOX_DRAWING = '\u2500'..'\u257F'

    private fun Char.isCyrillic(): Boolean = this in '\u0400'..'\u04FF'
}
