package com.kingwar.zipbook.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalOrderTest {

    private fun sorted(vararg names: String) = names.sortedWith(NaturalOrder)

    @Test
    fun `mixed zero padding orders numerically`() {
        // The exact case from the brief: 00.mp3 alongside 0001.mp3 and friends.
        assertEquals(
            listOf("00.mp3", "0001.mp3", "002.mp3", "9.mp3", "10.mp3", "0100.mp3"),
            sorted("10.mp3", "0001.mp3", "0100.mp3", "00.mp3", "9.mp3", "002.mp3"),
        )
    }

    @Test
    fun `plain lexicographic order would be wrong here`() {
        val input = listOf("2.mp3", "10.mp3", "1.mp3")
        assertEquals(listOf("1.mp3", "10.mp3", "2.mp3"), input.sorted())
        assertEquals(listOf("1.mp3", "2.mp3", "10.mp3"), input.sortedWith(NaturalOrder))
    }

    @Test
    fun `numbers beyond Int range do not overflow`() {
        assertEquals(
            listOf("f9999999999999999999.mp3", "f99999999999999999999.mp3"),
            sorted("f99999999999999999999.mp3", "f9999999999999999999.mp3"),
        )
    }

    @Test
    fun `digits sort before letters at the same position`() {
        assertEquals(
            listOf("01 - prologue.mp3", "intro.mp3"),
            sorted("intro.mp3", "01 - prologue.mp3"),
        )
    }

    @Test
    fun `case differences do not scramble numeric runs`() {
        assertEquals(
            listOf("Track 1.mp3", "track 2.mp3", "Track 10.mp3"),
            sorted("Track 10.mp3", "Track 1.mp3", "track 2.mp3"),
        )
    }

    @Test
    fun `cyrillic chapter names order by number`() {
        assertEquals(
            listOf("Глава 1.mp3", "Глава 2.mp3", "Глава 11.mp3", "Глава 101.mp3"),
            sorted("Глава 11.mp3", "Глава 101.mp3", "Глава 1.mp3", "Глава 2.mp3"),
        )
    }

    @Test
    fun `same value different padding is deterministic`() {
        val a = sorted("1.mp3", "01.mp3", "001.mp3")
        assertEquals(listOf("1.mp3", "01.mp3", "001.mp3"), a)
        // Comparator must be antisymmetric: reversing input gives the same output.
        assertEquals(a, sorted("001.mp3", "01.mp3", "1.mp3"))
    }

    @Test
    fun `path comparator groups directories before descending`() {
        val paths = listOf(
            "Том 10/01.mp3",
            "Том 2/10.mp3",
            "Том 2/2.mp3",
            "Том 1/01.mp3",
        )
        assertEquals(
            listOf("Том 1/01.mp3", "Том 2/2.mp3", "Том 2/10.mp3", "Том 10/01.mp3"),
            paths.sortedWith(NaturalOrder.pathComparator),
        )
    }

    @Test
    fun `comparator is transitive on a shuffled realistic set`() {
        val names = (0..120).map { "%04d.mp3".format(it) }.shuffled(kotlin.random.Random(7))
        val out = names.sortedWith(NaturalOrder)
        val numbers = out.map { it.removeSuffix(".mp3").toInt() }
        assertEquals((0..120).toList(), numbers)
        assertTrue(out.first() == "0000.mp3")
    }
}
