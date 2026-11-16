package com.kingwar.zipbook.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipNameDecoderTest {

    private val cyrillic = "Том 01 Глава 05.mp3"

    @Test
    fun `utf8 flagged names pass through unchanged`() {
        val raw = cyrillic.toByteArray(Charsets.UTF_8)
        assertEquals(cyrillic, ZipNameDecoder.decode(raw, utf8Flag = true))
    }

    @Test
    fun `cp866 names are recovered without the flag`() {
        val raw = cyrillic.toByteArray(charset("CP866"))
        assertEquals(cyrillic, ZipNameDecoder.decode(raw, utf8Flag = false))
    }

    @Test
    fun `windows-1251 names are recovered without the flag`() {
        val raw = cyrillic.toByteArray(charset("windows-1251"))
        assertEquals(cyrillic, ZipNameDecoder.decode(raw, utf8Flag = false))
    }

    @Test
    fun `utf8 without the flag is still recovered`() {
        val raw = cyrillic.toByteArray(Charsets.UTF_8)
        assertEquals(cyrillic, ZipNameDecoder.decode(raw, utf8Flag = false))
    }

    @Test
    fun `ascii names are untouched by every path`() {
        val name = "Chapter 01 - Prologue.mp3"
        assertEquals(name, ZipNameDecoder.decode(name.toByteArray(Charsets.UTF_8), true))
        assertEquals(name, ZipNameDecoder.decode(name.toByteArray(Charsets.UTF_8), false))
        assertEquals(name, ZipNameDecoder.decode(name.toByteArray(charset("CP866")), false))
    }

    @Test
    fun `longer russian title round-trips through cp866`() {
        val name = "Лесовик/Том 02/0001 Возвращение домой.mp3"
        val raw = name.toByteArray(charset("CP866"))
        assertEquals(name, ZipNameDecoder.decode(raw, utf8Flag = false))
    }

    @Test
    fun `mojibake repair recovers a wrongly decoded name`() {
        // Simulate what a fixed-charset reader produces: CP866 bytes read as latin-1.
        val broken = String(cyrillic.toByteArray(charset("CP866")), Charsets.ISO_8859_1)
        assertTrue("precondition: input must be broken", broken != cyrillic)
        assertEquals(cyrillic, ZipNameDecoder.repairMojibake(broken))
    }

    @Test
    fun `mojibake repair leaves a clean name alone`() {
        assertEquals(cyrillic, ZipNameDecoder.repairMojibake(cyrillic))
        val ascii = "01 - intro.mp3"
        assertEquals(ascii, ZipNameDecoder.repairMojibake(ascii))
    }

    @Test
    fun `path separators survive decoding`() {
        val name = "Книга/Глава 1/01.mp3"
        val raw = name.toByteArray(charset("windows-1251"))
        val out = ZipNameDecoder.decode(raw, utf8Flag = false)
        assertEquals(name, out)
        assertEquals(3, out.split('/').size)
    }
}
