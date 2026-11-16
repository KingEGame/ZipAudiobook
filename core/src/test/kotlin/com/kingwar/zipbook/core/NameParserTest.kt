package com.kingwar.zipbook.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NameParserTest {

    @Test
    fun `russian volume forms`() {
        assertEquals(3, NameParser.parse("Том 3").volume)
        assertEquals(3, NameParser.parse("том_03").volume)
        assertEquals(3, NameParser.parse("tom3").volume)
        assertEquals(2, NameParser.parse("Книга 2").volume)
        assertEquals(12, NameParser.parse("Том №12").volume)
    }

    @Test
    fun `english volume forms`() {
        assertEquals(2, NameParser.parse("Vol. 2").volume)
        assertEquals(2, NameParser.parse("Volume 02").volume)
        assertEquals(1, NameParser.parse("Book 1").volume)
    }

    @Test
    fun `chapter forms`() {
        assertEquals(12, NameParser.parse("Глава 12.mp3").chapter)
        assertEquals(12, NameParser.parse("глава-012.mp3").chapter)
        assertEquals(7, NameParser.parse("гл 7.mp3").chapter)
        assertEquals(5, NameParser.parse("Chapter 5.mp3").chapter)
        assertEquals(5, NameParser.parse("Ch. 05.mp3").chapter)
        assertEquals(7, NameParser.parse("Track 7.mp3").chapter)
    }

    @Test
    fun `part and disc forms`() {
        assertEquals(4, NameParser.parse("Часть 4").part)
        assertEquals(2, NameParser.parse("CD2").part)
        assertEquals(3, NameParser.parse("Disc 03").part)
        assertEquals(2, NameParser.parse("Диск 2").part)
    }

    @Test
    fun `bare numbers are reported as bare not as chapter`() {
        val h = NameParser.parse("0001.mp3")
        assertEquals(1, h.bare)
        assertNull(h.chapter)
        assertNull(h.volume)
    }

    @Test
    fun `zero is a valid bare number`() {
        assertEquals(0, NameParser.parse("00.mp3").bare)
    }

    @Test
    fun `leading number with title is split`() {
        val h = NameParser.parse("01 - Пролог.mp3")
        assertEquals(1, h.bare)
        assertEquals("Пролог", h.cleanTitle)
    }

    @Test
    fun `dotted number with title is split`() {
        val h = NameParser.parse("12. Возвращение.mp3")
        assertEquals(12, h.bare)
        assertEquals("Возвращение", h.cleanTitle)
    }

    @Test
    fun `keyword number is stripped from the title`() {
        val h = NameParser.parse("Том 02 - Тёмный лес")
        assertEquals(2, h.volume)
        assertEquals("Тёмный лес", h.cleanTitle)
    }

    @Test
    fun `a number inside a word is not a chapter`() {
        // "mp3" must not read as chapter 3, and a bitrate tag must not become a number.
        val h = NameParser.parse("intro.mp3")
        assertNull(h.chapter)
        assertNull(h.volume)
        assertNull(h.bare)
    }

    @Test
    fun `keyword does not match inside a longer word`() {
        // "Чтение 5" contains no volume keyword; "Глухарь 3" must not match "гл".
        assertNull(NameParser.parse("Глухарь 3.mp3").chapter)
        assertNull(NameParser.parse("Tomsk 5.mp3").volume)
    }

    @Test
    fun `volume and chapter can coexist`() {
        val h = NameParser.parse("Том 2 Глава 15.mp3")
        assertEquals(2, h.volume)
        assertEquals(15, h.chapter)
    }
}
