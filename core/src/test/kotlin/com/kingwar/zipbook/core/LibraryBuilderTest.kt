package com.kingwar.zipbook.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryBuilderTest {

    private fun entries(vararg paths: String, size: Long = 1_000_000L) =
        paths.map { AudioEntry(it, size) }

    @Test
    fun `flat archive with mixed padding becomes one book in numeric order`() {
        // Archive iteration order is deliberately scrambled.
        val lib = LibraryBuilder.build(
            "Книга.zip",
            entries("0010.mp3", "00.mp3", "0002.mp3", "0001.mp3"),
        )
        assertEquals(Layout.FLAT_SINGLE_BOOK, lib.layout)
        assertEquals(1, lib.books.size)
        assertEquals(
            listOf("00.mp3", "0001.mp3", "0002.mp3", "0010.mp3"),
            lib.books[0].chapters.map { it.entry.fileName },
        )
        // Filenames carry distinct numbers, so they are trusted as chapter numbers.
        assertEquals(listOf(0, 1, 2, 10), lib.books[0].chapters.map { it.number })
    }

    @Test
    fun `flat archive title falls back to the archive name`() {
        val lib = LibraryBuilder.build("Лесовик_Том1.zip", entries("01.mp3", "02.mp3"))
        assertEquals("Лесовик Том1", lib.books[0].title)
    }

    @Test
    fun `directories named as volumes become a series`() {
        val lib = LibraryBuilder.build(
            "series.zip",
            entries(
                "Лесовик/Том 02/0001.mp3",
                "Лесовик/Том 02/0002.mp3",
                "Лесовик/Том 01/0002.mp3",
                "Лесовик/Том 01/0001.mp3",
                "Лесовик/Том 10/0001.mp3",
            ),
        )
        assertEquals(Layout.SERIES_WITH_VOLUMES, lib.layout)
        assertEquals("Лесовик", lib.books[0].series)
        assertEquals(listOf(1, 2, 10), lib.books.map { it.seriesIndex })
        // Volume 10 must come after volume 2, not after volume 1.
        assertEquals(
            listOf("Лесовик/Том 01", "Лесовик/Том 02", "Лесовик/Том 10"),
            lib.books.map { it.sourceDirectory },
        )
    }

    @Test
    fun `each volume keeps its own chapters in order`() {
        val lib = LibraryBuilder.build(
            "series.zip",
            entries(
                "Том 1/0002.mp3",
                "Том 1/0001.mp3",
                "Том 2/0001.mp3",
            ),
        )
        assertEquals(2, lib.books.size)
        assertEquals(listOf("0001.mp3", "0002.mp3"), lib.books[0].chapters.map { it.entry.fileName })
        assertEquals(1, lib.books[1].chapters.size)
    }

    @Test
    fun `distinct named directories become one book each`() {
        val lib = LibraryBuilder.build(
            "two.zip",
            entries("Первая книга/01.mp3", "Вторая книга/01.mp3"),
        )
        assertEquals(Layout.DIRECTORY_PER_BOOK, lib.layout)
        assertEquals(2, lib.books.size)
        assertNull(lib.books[0].series)
    }

    @Test
    fun `archiver junk is skipped with a reason`() {
        val lib = LibraryBuilder.build(
            "junk.zip",
            entries(
                "__MACOSX/._01.mp3",
                "._01.mp3",
                ".DS_Store",
                "info.txt",
                "01.mp3",
            ),
        )
        assertEquals(1, lib.chapterCount)
        val reasons = lib.skipped.associate { it.path to it.reason }
        assertEquals("archiver metadata", reasons["__MACOSX/._01.mp3"])
        assertEquals("macOS resource fork", reasons["._01.mp3"])
        assertEquals("system file", reasons[".DS_Store"])
        assertEquals("not audio (.txt)", reasons["info.txt"])
    }

    @Test
    fun `zero length entries are skipped`() {
        val lib = LibraryBuilder.build(
            "z.zip",
            listOf(AudioEntry("01.mp3", 0L), AudioEntry("02.mp3", 500L)),
        )
        assertEquals(1, lib.chapterCount)
        assertEquals("empty entry", lib.skipped.single().reason)
    }

    @Test
    fun `all supported audio extensions are accepted`() {
        val paths = LibraryBuilder.AUDIO_EXTENSIONS.mapIndexed { i, ext -> "%03d.%s".format(i, ext) }
        val lib = LibraryBuilder.build("all.zip", entries(*paths.toTypedArray()))
        assertEquals(paths.size, lib.chapterCount)
        assertTrue(lib.skipped.isEmpty())
    }

    @Test
    fun `duplicate filename numbers fall back to positional numbering`() {
        // Two discs flattened into one directory: 01,02 appear twice. Trusting the
        // filename number would produce duplicate chapter numbers, so position wins.
        val lib = LibraryBuilder.build(
            "dup.zip",
            entries("cd1_01.mp3", "cd1_02.mp3", "cd2_01.mp3", "cd2_02.mp3"),
        )
        assertEquals(listOf(1, 2, 3, 4), lib.books[0].chapters.map { it.number })
    }

    @Test
    fun `audio in root alongside subdirectories is reported as mixed`() {
        val lib = LibraryBuilder.build(
            "mixed.zip",
            entries("intro.mp3", "Том 1/01.mp3"),
        )
        assertEquals(Layout.MIXED, lib.layout)
        assertEquals(2, lib.books.size)
    }

    @Test
    fun `empty archive yields no books and does not crash`() {
        val lib = LibraryBuilder.build("empty.zip", emptyList())
        assertTrue(lib.books.isEmpty())
        assertEquals(0, lib.chapterCount)
    }

    @Test
    fun `chapter titles keep text when the filename has one`() {
        val lib = LibraryBuilder.build(
            "t.zip",
            entries("01 - Пролог.mp3", "02 - Дорога.mp3"),
        )
        assertEquals(listOf("1. Пролог", "2. Дорога"), lib.books[0].chapters.map { it.title })
    }

    @Test
    fun `chapter titles fall back to a generated label`() {
        val lib = LibraryBuilder.build("t.zip", entries("0001.mp3", "0002.mp3"))
        assertEquals(listOf("Глава 1", "Глава 2"), lib.books[0].chapters.map { it.title })
    }
}
