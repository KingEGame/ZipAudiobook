package com.kingwar.zipbook.core

/** Playback-ready chapter: one audio entry plus its resolved display info. */
data class Chapter(
    val entry: AudioEntry,
    val index: Int,
    val number: Int,
    val title: String,
)

/** One book (a volume of a series, or a standalone book). */
data class Book(
    val id: String,
    val title: String,
    /** Series name when detected, else null. */
    val series: String? = null,
    /** Position in the series when detected, else null. */
    val seriesIndex: Int? = null,
    val author: String? = null,
    val chapters: List<Chapter>,
    /** Archive path of the directory this book came from ("" for archive root). */
    val sourceDirectory: String,
    /**
     * Archive path of the image chosen as this book's cover, or null when the archive
     * carries no usable image. Art embedded in the audio itself is handled separately,
     * at import time, because it requires reading the file's bytes.
     */
    val coverEntryPath: String? = null,
) {
    val totalBytes: Long get() = chapters.sumOf { it.entry.sizeBytes }
}

/** Result of analysing one archive. */
data class ArchiveLibrary(
    val archiveName: String,
    val books: List<Book>,
    val layout: Layout,
    /** Entries skipped and why, surfaced in the import screen so nothing vanishes silently. */
    val skipped: List<SkippedEntry> = emptyList(),
) {
    val chapterCount: Int get() = books.sumOf { it.chapters.size }
}

data class SkippedEntry(val path: String, val reason: String)

/** How the archive was organised, reported so the UI can explain its grouping. */
enum class Layout {
    /** All audio in the archive root: one book, chapters ordered naturally. */
    FLAT_SINGLE_BOOK,

    /** One directory level, each directory a book. */
    DIRECTORY_PER_BOOK,

    /** Directories recognised as volumes of one series (Том 01, Том 02...). */
    SERIES_WITH_VOLUMES,

    /** Deeper nesting (Author/Series/Volume) collapsed to leaf directories. */
    NESTED,

    /** Audio both in root and in subdirectories; root files become their own book. */
    MIXED,
}

/**
 * Turns a flat list of archive entries into a playable library.
 *
 * Rules, applied in order:
 *  1. Non-audio entries, archiver junk (`__MACOSX/`, `._*`, `.DS_Store`, `Thumbs.db`)
 *     and zero-length entries are dropped, each recorded in [ArchiveLibrary.skipped].
 *  2. Audio entries are grouped by their containing directory. A directory holding at
 *     least one audio file is a *book candidate*; its subdirectory audio stays with the
 *     subdirectory, so `Disc 1/`+`Disc 2/` become separate candidates and are then
 *     merged back when they parse as parts of the same volume.
 *  3. Candidates whose directory names parse as volume/part numbers of a common parent
 *     become volumes of one series ([Layout.SERIES_WITH_VOLUMES]); the series name comes
 *     from the common parent directory, else from the archive name.
 *  4. Chapters inside each book are ordered with [NaturalOrder] over the full entry path,
 *     never by archive iteration order (which is arbitrary and frequently wrong).
 *  5. Chapter numbers come from the filename when it carries one, else from position.
 */
object LibraryBuilder {

    val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac", "wav", "wma", "mp4", "mka",
    )

    private val JUNK_PREFIXES = listOf("__MACOSX/", "__macosx/")
    private val JUNK_NAMES = setOf(".ds_store", "thumbs.db", "desktop.ini")

    fun build(archiveName: String, entries: List<AudioEntry>): ArchiveLibrary {
        val skipped = mutableListOf<SkippedEntry>()
        val images = mutableListOf<AudioEntry>()
        val audio = entries.filter { entry ->
            val name = entry.fileName.lowercase()
            when {
                JUNK_PREFIXES.any { entry.path.startsWith(it) } -> {
                    skipped += SkippedEntry(entry.path, "archiver metadata"); false
                }
                name.startsWith("._") -> {
                    skipped += SkippedEntry(entry.path, "macOS resource fork"); false
                }
                name in JUNK_NAMES -> {
                    skipped += SkippedEntry(entry.path, "system file"); false
                }
                entry.extension in CoverFinder.IMAGE_EXTENSIONS -> {
                    // Held back rather than skipped: it may be this book's cover.
                    if (entry.sizeBytes > 0L) images += entry
                    else skipped += SkippedEntry(entry.path, "empty entry")
                    false
                }
                entry.extension !in AUDIO_EXTENSIONS -> {
                    skipped += SkippedEntry(entry.path, "not audio (.${entry.extension})"); false
                }
                entry.sizeBytes <= 0L -> {
                    skipped += SkippedEntry(entry.path, "empty entry"); false
                }
                else -> true
            }
        }

        if (audio.isEmpty()) {
            images.forEach { skipped += SkippedEntry(it.path, "image without audio") }
            return ArchiveLibrary(archiveName, emptyList(), Layout.FLAT_SINGLE_BOOK, skipped)
        }

        val byDirectory: Map<String, List<AudioEntry>> = audio
            .groupBy { it.directory }
            .mapValues { (_, list) -> list.sortedWith(compareBy(NaturalOrder.pathComparator) { it.path }) }

        val archiveTitle = archiveName.substringBeforeLast('.', archiveName)
            .replace('_', ' ')
            .trim()

        // Case 1: everything in a single directory (root or not) -> one book.
        if (byDirectory.size == 1) {
            val (dir, list) = byDirectory.entries.first()
            val dirHints = dir.substringAfterLast('/').takeIf { it.isNotBlank() }?.let { NameParser.parse(it) }
            val title = dirHints?.cleanTitle?.takeIf { it.isNotBlank() }
                ?: dir.substringAfterLast('/').takeIf { it.isNotBlank() }
                ?: archiveTitle
            val book = makeBook(
                id = "0",
                title = title,
                series = null,
                seriesIndex = dirHints?.volume ?: dirHints?.bare,
                entries = list,
                sourceDirectory = dir,
                coverEntryPath = CoverFinder.pick(images, dir)?.path,
            )
            val layout = if (dir.isEmpty()) Layout.FLAT_SINGLE_BOOK else Layout.DIRECTORY_PER_BOOK
            noteUnusedImages(images, listOf(book), skipped)
            return ArchiveLibrary(archiveName, listOf(book), layout, skipped)
        }

        // Case 2+: several directories. Decide whether they are volumes of one series.
        val dirs = byDirectory.keys.sortedWith(NaturalOrder.pathComparator)
        val parents = dirs.map { it.substringBeforeLast('/', "") }.distinct()
        val leafNames = dirs.associateWith { it.substringAfterLast('/') }
        val leafHints = leafNames.mapValues { (_, name) -> NameParser.parse(name) }

        val numbered = leafHints.count { (_, h) -> (h.volume ?: h.part ?: h.bare) != null }
        val allUnderOneParent = parents.size == 1
        val mostlyNumbered = numbered >= dirs.size - 1 && dirs.size >= 2

        val layout = when {
            // Audio sitting in the archive root next to subdirectories is reported as
            // MIXED before anything else: the root files are a book of their own, and
            // calling this a clean series would hide them behind a volume label.
            byDirectory.containsKey("") -> Layout.MIXED
            allUnderOneParent && mostlyNumbered -> Layout.SERIES_WITH_VOLUMES
            dirs.all { it.count { c -> c == '/' } == 0 } -> Layout.DIRECTORY_PER_BOOK
            else -> Layout.NESTED
        }

        val seriesName: String? = when (layout) {
            Layout.SERIES_WITH_VOLUMES -> parents.first().substringAfterLast('/')
                .takeIf { it.isNotBlank() } ?: archiveTitle
            else -> null
        }

        val books = dirs.mapIndexed { idx, dir ->
            val list = byDirectory.getValue(dir)
            val hints = leafHints[dir]
            val leaf = leafNames[dir].orEmpty()
            val title = when {
                leaf.isBlank() -> archiveTitle
                hints != null && hints.cleanTitle.isNotBlank() -> hints.cleanTitle
                else -> leaf
            }
            makeBook(
                id = idx.toString(),
                title = title,
                series = seriesName,
                seriesIndex = hints?.volume ?: hints?.part ?: hints?.bare ?: (idx + 1).takeIf { seriesName != null },
                entries = list,
                sourceDirectory = dir,
                coverEntryPath = CoverFinder.pick(images, dir)?.path,
            )
        }

        noteUnusedImages(images, books, skipped)
        return ArchiveLibrary(archiveName, books, layout, skipped)
    }

    /** Images no book claimed are reported, so a stray scan is visible, not silently lost. */
    private fun noteUnusedImages(
        images: List<AudioEntry>,
        books: List<Book>,
        skipped: MutableList<SkippedEntry>,
    ) {
        val used = books.mapNotNull { it.coverEntryPath }.toSet()
        images.filterNot { it.path in used }
            .forEach { skipped += SkippedEntry(it.path, "image not used as cover") }
    }

    private fun makeBook(
        id: String,
        title: String,
        series: String?,
        seriesIndex: Int?,
        entries: List<AudioEntry>,
        sourceDirectory: String,
        coverEntryPath: String? = null,
    ): Book {
        // Does every filename carry its own number? If so trust it; otherwise number
        // by position so a book never shows duplicate or missing chapter numbers.
        val hints = entries.map { NameParser.parse(it.fileName) }
        val explicit = hints.map { it.chapter ?: it.bare }
        val useExplicit = explicit.all { it != null } && explicit.distinct().size == explicit.size

        val chapters = entries.mapIndexed { i, entry ->
            val h = hints[i]
            val number = if (useExplicit) explicit[i]!! else i + 1
            val cleaned = h.cleanTitle.takeIf { it.isNotBlank() && !it.all { c -> c.isDigit() } }
            Chapter(
                entry = entry,
                index = i,
                number = number,
                title = cleaned?.let { "$number. $it" } ?: "Глава $number",
            )
        }

        return Book(
            id = id,
            title = title,
            series = series,
            seriesIndex = seriesIndex,
            chapters = chapters,
            sourceDirectory = sourceDirectory,
            coverEntryPath = coverEntryPath,
        )
    }
}
