package com.kingwar.zipbook.core

/**
 * Human ("natural") ordering for filenames that embed numbers.
 *
 * Plain lexicographic ordering breaks real audiobook rips in two common ways:
 *   - mixed zero padding: "9.mp3" sorts after "10.mp3" only if compared numerically
 *   - mixed widths in one archive: "00.mp3", "0001.mp3", "002.mp3"
 *
 * This comparator splits each name into alternating runs of digits and non-digits.
 * Digit runs compare by numeric value (leading zeros ignored, arbitrary length via
 * length-then-lexicographic on the trimmed digits, so no overflow). Non-digit runs
 * compare case-insensitively first so "Chapter" and "chapter" interleave predictably,
 * falling back to a stable case-sensitive compare.
 *
 * Ties on value are broken by the raw digit run so "01" and "1" keep a deterministic
 * order instead of depending on archive iteration order.
 */
object NaturalOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            val aDigit = ca.isAsciiDigit()
            val bDigit = cb.isAsciiDigit()

            if (aDigit && bDigit) {
                val endA = runEnd(a, i) { it.isAsciiDigit() }
                val endB = runEnd(b, j) { it.isAsciiDigit() }
                val cmp = compareNumericRun(a, i, endA, b, j, endB)
                if (cmp != 0) return cmp
                i = endA
                j = endB
            } else if (!aDigit && !bDigit) {
                val endA = runEnd(a, i) { !it.isAsciiDigit() }
                val endB = runEnd(b, j) { !it.isAsciiDigit() }
                val sa = a.substring(i, endA)
                val sb = b.substring(j, endB)
                val cmp = sa.compareTo(sb, ignoreCase = true)
                if (cmp != 0) return cmp
                i = endA
                j = endB
            } else {
                // Digits sort before letters: "01 - intro" before "intro".
                return if (aDigit) -1 else 1
            }
        }
        if (i < a.length) return 1
        if (j < b.length) return -1
        // Identical under natural rules; fall back to raw compare for stability.
        return a.compareTo(b)
    }

    /** Compares two digit runs by numeric value without parsing to a number. */
    private fun compareNumericRun(
        a: String, aStart: Int, aEnd: Int,
        b: String, bStart: Int, bEnd: Int,
    ): Int {
        var ia = aStart
        var ib = bStart
        while (ia < aEnd - 1 && a[ia] == '0') ia++
        while (ib < bEnd - 1 && b[ib] == '0') ib++
        val lenA = aEnd - ia
        val lenB = bEnd - ib
        if (lenA != lenB) return lenA - lenB
        var k = 0
        while (k < lenA) {
            val d = a[ia + k] - b[ib + k]
            if (d != 0) return d
            k++
        }
        // Same value: shorter padding first ("1" before "01").
        return (aEnd - aStart) - (bEnd - bStart)
    }

    private inline fun runEnd(s: String, from: Int, predicate: (Char) -> Boolean): Int {
        var k = from
        while (k < s.length && predicate(s[k])) k++
        return k
    }

    /** Compares full archive paths segment by segment so directories group correctly. */
    val pathComparator: Comparator<String> = Comparator { a, b ->
        val pa = a.split('/')
        val pb = b.split('/')
        val n = minOf(pa.size, pb.size)
        for (k in 0 until n) {
            // A directory segment always sorts before a file segment at the same depth
            // only when depths differ; same-depth segments use natural order.
            val cmp = compare(pa[k], pb[k])
            if (cmp != 0) return@Comparator cmp
        }
        pa.size - pb.size
    }
}

internal fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
