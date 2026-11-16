package com.kingwar.zipbook.core

/**
 * Picks the cover image for a book out of the non-audio entries of an archive.
 *
 * Real rips name the cover in a handful of ways and often ship several images
 * (`cover.jpg`, `front.jpg`, scans of the back and the spine, a `thumbs/` folder).
 * Choosing by priority rather than by "first image found" avoids showing the barcode
 * on the back of the box as the book's cover.
 *
 * Selection order inside a book's own directory:
 *   1. a name that means "cover": cover, обложка, front, folder, album, albumart, art
 *   2. any other image in that directory, largest first
 * If the book directory has no image, the search widens to the parent directory and
 * then the archive root, because a series archive usually keeps one cover for all
 * volumes at `Series/cover.jpg`.
 */
object CoverFinder {

    val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

    /** Names that explicitly mean "this is the cover", highest priority first. */
    private val PREFERRED_STEMS = listOf(
        "cover", "обложка", "oblozhka", "front", "folder", "albumart", "album", "art", "thumb",
    )

    /** Directories whose images are decoration, not the cover. */
    private val IGNORED_DIR_WORDS = listOf("thumbs", "thumbnails", "scan", "scans", "booklet")

    /**
     * @param images every non-audio image entry in the archive
     * @param bookDirectory the directory the book's audio lives in ("" for archive root)
     */
    fun pick(images: List<AudioEntry>, bookDirectory: String): AudioEntry? {
        if (images.isEmpty()) return null

        val candidates = images.filter { it.extension in IMAGE_EXTENSIONS }
        if (candidates.isEmpty()) return null

        // Widen the search outwards: own directory, then each ancestor, then root.
        val scopes = buildList {
            add(bookDirectory)
            var dir = bookDirectory
            while (dir.contains('/')) {
                dir = dir.substringBeforeLast('/')
                add(dir)
            }
            if (bookDirectory.isNotEmpty()) add("")
        }.distinct()

        for (scope in scopes) {
            val inScope = candidates.filter { it.directory == scope }
            best(inScope)?.let { return it }
        }

        // Nothing in any ancestor: fall back to the single best image anywhere, but only
        // when the archive has exactly one plausible cover, so unrelated volume art is
        // not borrowed across books.
        val global = candidates.filter { img ->
            IGNORED_DIR_WORDS.none { w -> img.directory.lowercase().contains(w) }
        }
        return if (global.size == 1) global.first() else null
    }

    private fun best(images: List<AudioEntry>): AudioEntry? {
        if (images.isEmpty()) return null
        val usable = images.filter { img ->
            IGNORED_DIR_WORDS.none { w -> img.directory.lowercase().contains(w) }
        }.ifEmpty { images }

        return usable.minWithOrNull(
            compareBy<AudioEntry> { priority(it) }
                // Within the same priority prefer the bigger file: the full-size cover
                // beats a tiny thumbnail sitting next to it.
                .thenByDescending { it.sizeBytes }
                .thenBy(NaturalOrder) { it.fileName },
        )
    }

    /** Lower is better. */
    private fun priority(entry: AudioEntry): Int {
        val stem = entry.baseName.lowercase()
        PREFERRED_STEMS.forEachIndexed { index, word ->
            if (stem == word) return index
            if (stem.startsWith(word) || stem.endsWith(word)) return PREFERRED_STEMS.size + index
        }
        return PREFERRED_STEMS.size * 2 + 1
    }
}
