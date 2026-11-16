package com.kingwar.zipbook.data

import kotlinx.serialization.Serializable

/** One playable chapter after import: a real file in app storage. */
@Serializable
data class StoredChapter(
    val index: Int,
    val number: Int,
    val title: String,
    /** Path relative to the app's books directory. */
    val relativePath: String,
    val sizeBytes: Long,
    /** Filled in once the player has reported a duration; -1 until then. */
    val durationMs: Long = -1L,
    /** Original path inside the archive, kept for troubleshooting an odd grouping. */
    val archivePath: String = "",
)

/** One imported book (a volume of a series, or a standalone book). */
@Serializable
data class StoredBook(
    val id: String,
    val title: String,
    val series: String? = null,
    val seriesIndex: Int? = null,
    val chapters: List<StoredChapter>,
    val archiveName: String,
    val layout: String,
    val importedAtEpochMs: Long,
    /**
     * Path (relative to the books directory) of the extracted cover image, or null when
     * neither the archive nor the audio tags carried one.
     */
    val coverPath: String? = null,
    /** Where the cover came from, shown on the book screen: "archive" or "id3". */
    val coverSource: String? = null,
    /** Narrator/author read from the audio's ID3 TPE1 tag, when present. */
    val author: String? = null,
) {
    val totalBytes: Long get() = chapters.sumOf { it.sizeBytes }

    val displayTitle: String
        get() = when {
            series != null && seriesIndex != null -> "$series — том $seriesIndex"
            series != null -> "$series — $title"
            else -> title
        }
}

/** Where the listener stopped in a given book. */
@Serializable
data class Progress(
    val bookId: String,
    val chapterIndex: Int,
    val positionMs: Long,
    val updatedAtEpochMs: Long,
    val finished: Boolean = false,
)

/** A saved spot the listener wants to return to. */
@Serializable
data class Bookmark(
    val bookId: String,
    val chapterIndex: Int,
    val positionMs: Long,
    /** Shown in the list — the chapter title captured when the bookmark was made. */
    val label: String,
    val createdAtEpochMs: Long,
)

/** The whole persisted state, written as one JSON file. */
@Serializable
data class LibraryState(
    val books: List<StoredBook> = emptyList(),
    val progress: Map<String, Progress> = emptyMap(),
    /** Playback speed is a per-library preference, as in every serious player. */
    val playbackSpeed: Float = 1.0f,
    /** User bookmarks across all books; added with a default so old files still load. */
    val bookmarks: List<Bookmark> = emptyList(),
    val schemaVersion: Int = 1,
)

/** Books grouped for display: series, repeat titles, same-author clusters, and the rest. */
data class LibraryView(
    val series: List<SeriesGroup>,
    val titleGroups: List<TitleGroup> = emptyList(),
    val authorGroups: List<AuthorGroup> = emptyList(),
    val standalone: List<StoredBook>,
) {
    val isEmpty: Boolean
        get() = series.isEmpty() && titleGroups.isEmpty() && authorGroups.isEmpty() && standalone.isEmpty()
}

data class SeriesGroup(
    val name: String,
    val volumes: List<StoredBook>,
) {
    val chapterCount: Int get() = volumes.sumOf { it.chapters.size }
}

/**
 * Two or more non-series books sharing the same title — typically the same audiobook
 * imported more than once (a re-download, a retry, a slightly different archive name).
 * Nothing is merged or deleted automatically; they are just shown together so the
 * duplicate is obvious instead of hiding in a flat list.
 */
data class TitleGroup(
    val title: String,
    val volumes: List<StoredBook>,
) {
    val chapterCount: Int get() = volumes.sumOf { it.chapters.size }
}

/**
 * Two or more standalone books (not already grouped by series or title) sharing the
 * same author tag, grouped under the author's name — the same idea as a series group,
 * but driven by the ID3 TPE1 tag rather than a folder name.
 */
data class AuthorGroup(
    val author: String,
    val books: List<StoredBook>,
) {
    val chapterCount: Int get() = books.sumOf { it.chapters.size }
}

/** Case/whitespace-insensitive key so "Властелин Колец " and "властелин колец" match. */
private fun StoredBook.titleKey(): String = title.trim().lowercase()
private fun StoredBook.authorKey(): String? = author?.trim()?.takeIf { it.isNotBlank() }?.lowercase()

fun List<StoredBook>.toLibraryView(): LibraryView {
    val (inSeries, notInSeries) = partition { it.series != null }

    val seriesGroups = inSeries
        .groupBy { it.series!! }
        .map { (name, volumes) ->
            SeriesGroup(
                name = name,
                volumes = volumes.sortedWith(
                    compareBy(nullsLast()) { it.seriesIndex },
                ),
            )
        }
        .sortedBy { it.name }

    // Step 1: cluster same-title books first — these are almost always duplicate
    // imports of the same audiobook, so title is a stronger signal than author.
    val byTitle = notInSeries.groupBy { it.titleKey() }
    val titleGroups = byTitle.values
        .filter { it.size > 1 }
        .map { books ->
            TitleGroup(
                title = books.first().title,
                volumes = books.sortedByDescending { it.importedAtEpochMs },
            )
        }
        .sortedBy { it.title }
    val titledIds = titleGroups.flatMapTo(mutableSetOf()) { group -> group.volumes.map { it.id } }

    // Step 2: among what's left, cluster by author (ID3 TPE1 tag) when it repeats.
    val remaining = notInSeries.filterNot { it.id in titledIds }
    val byAuthor = remaining.filter { it.authorKey() != null }.groupBy { it.authorKey()!! }
    val authorGroups = byAuthor.values
        .filter { it.size > 1 }
        .map { books ->
            AuthorGroup(
                author = books.first().author!!.trim(),
                books = books.sortedByDescending { it.importedAtEpochMs },
            )
        }
        .sortedBy { it.author }
    val authoredIds = authorGroups.flatMapTo(mutableSetOf()) { group -> group.books.map { it.id } }

    val standalone = remaining.filterNot { it.id in authoredIds }
        .sortedByDescending { it.importedAtEpochMs }

    return LibraryView(
        series = seriesGroups,
        titleGroups = titleGroups,
        authorGroups = authorGroups,
        standalone = standalone,
    )
}
