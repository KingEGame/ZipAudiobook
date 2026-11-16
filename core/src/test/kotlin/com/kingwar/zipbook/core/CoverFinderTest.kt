package com.kingwar.zipbook.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverFinderTest {

    private fun img(path: String, size: Long = 50_000L) = AudioEntry(path, size)

    @Test
    fun `cover name wins over other images in the same directory`() {
        val picked = CoverFinder.pick(
            listOf(img("back.jpg"), img("cover.jpg"), img("spine.jpg")),
            bookDirectory = "",
        )
        assertEquals("cover.jpg", picked?.path)
    }

    @Test
    fun `russian cover name is recognised`() {
        val picked = CoverFinder.pick(listOf(img("разворот.jpg"), img("обложка.jpg")), "")
        assertEquals("обложка.jpg", picked?.path)
    }

    @Test
    fun `front and folder names are recognised`() {
        assertEquals("front.png", CoverFinder.pick(listOf(img("disc.png"), img("front.png")), "")?.path)
        assertEquals("folder.jpg", CoverFinder.pick(listOf(img("folder.jpg")), "")?.path)
    }

    @Test
    fun `book directory image beats an image in the parent`() {
        val picked = CoverFinder.pick(
            listOf(img("Серия/cover.jpg"), img("Серия/Том 01/cover.jpg")),
            bookDirectory = "Серия/Том 01",
        )
        assertEquals("Серия/Том 01/cover.jpg", picked?.path)
    }

    @Test
    fun `series cover in the parent is used when the volume has none`() {
        val picked = CoverFinder.pick(
            listOf(img("Серия/cover.jpg")),
            bookDirectory = "Серия/Том 02",
        )
        assertEquals("Серия/cover.jpg", picked?.path)
    }

    @Test
    fun `thumbnail directories are not used as the cover`() {
        val picked = CoverFinder.pick(
            listOf(img("thumbs/cover.jpg"), img("scans/page1.jpg")),
            bookDirectory = "",
        )
        assertNull(picked)
    }

    @Test
    fun `larger image wins among equally named candidates`() {
        val picked = CoverFinder.pick(
            listOf(img("img_a.jpg", size = 10_000), img("img_b.jpg", size = 900_000)),
            bookDirectory = "",
        )
        assertEquals("img_b.jpg", picked?.path)
    }

    @Test
    fun `non image files are ignored`() {
        assertNull(CoverFinder.pick(listOf(img("info.txt"), img("book.pdf")), ""))
    }

    @Test
    fun `no images yields null`() {
        assertNull(CoverFinder.pick(emptyList(), ""))
    }

    @Test
    fun `a single image elsewhere in the archive is borrowed`() {
        val picked = CoverFinder.pick(listOf(img("art/picture.jpg")), bookDirectory = "Том 01")
        assertEquals("art/picture.jpg", picked?.path)
    }

    @Test
    fun `several unrelated images elsewhere are not borrowed`() {
        val picked = CoverFinder.pick(
            listOf(img("Том 01/a.jpg"), img("Том 02/b.jpg")),
            bookDirectory = "Том 03",
        )
        assertNull(picked)
    }

    @Test
    fun `builder attaches the per-volume cover to each book`() {
        val lib = LibraryBuilder.build(
            "series.zip",
            listOf(
                AudioEntry("Серия/Том 01/0001.mp3", 1000),
                AudioEntry("Серия/Том 01/cover.jpg", 2000),
                AudioEntry("Серия/Том 02/0001.mp3", 1000),
                AudioEntry("Серия/Том 02/cover.jpg", 2000),
            ),
        )
        assertEquals(2, lib.books.size)
        assertEquals("Серия/Том 01/cover.jpg", lib.books[0].coverEntryPath)
        assertEquals("Серия/Том 02/cover.jpg", lib.books[1].coverEntryPath)
        assertEquals("covers must not be reported as skipped", 0, lib.skipped.size)
    }

    @Test
    fun `one series cover is shared by every volume`() {
        val lib = LibraryBuilder.build(
            "series.zip",
            listOf(
                AudioEntry("Серия/cover.jpg", 2000),
                AudioEntry("Серия/Том 01/0001.mp3", 1000),
                AudioEntry("Серия/Том 02/0001.mp3", 1000),
            ),
        )
        assertEquals(2, lib.books.size)
        assertEquals(
            listOf("Серия/cover.jpg", "Серия/cover.jpg"),
            lib.books.map { it.coverEntryPath },
        )
    }

    @Test
    fun `unused images are reported instead of vanishing`() {
        val lib = LibraryBuilder.build(
            "x.zip",
            listOf(
                AudioEntry("0001.mp3", 1000),
                AudioEntry("cover.jpg", 2000),
                AudioEntry("scans/page1.jpg", 2000),
            ),
        )
        assertEquals("cover.jpg", lib.books[0].coverEntryPath)
        assertEquals("image not used as cover", lib.skipped.single().reason)
        assertEquals("scans/page1.jpg", lib.skipped.single().path)
    }

    @Test
    fun `an archive of images only produces no books and says why`() {
        val lib = LibraryBuilder.build("pics.zip", listOf(AudioEntry("cover.jpg", 2000)))
        assertEquals(0, lib.books.size)
        assertEquals("image without audio", lib.skipped.single().reason)
    }
}
