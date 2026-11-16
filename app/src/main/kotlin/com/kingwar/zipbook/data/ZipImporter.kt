package com.kingwar.zipbook.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.kingwar.zipbook.core.ArchiveLibrary
import com.kingwar.zipbook.core.Id3CoverExtractor
import com.kingwar.zipbook.core.Id3TagReader
import com.kingwar.zipbook.core.ImageSniffer
import com.kingwar.zipbook.core.LibraryBuilder
import com.kingwar.zipbook.core.ZipReader
import com.kingwar.zipbook.core.ZipSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/** What the import screen shows before anything is written to storage. */
data class ImportPreview(
    val archiveName: String,
    val library: ArchiveLibrary,
)

/** Progress callback payload while extracting. */
data class ImportProgress(
    val fraction: Float,
    val currentFile: String,
    val filesDone: Int,
    val filesTotal: Int,
)

sealed interface ImportResult {
    data class Success(val books: List<StoredBook>) : ImportResult
    data class Failure(val message: String) : ImportResult
}

/**
 * Turns a picked ZIP into playable books.
 *
 * The archive is read in place through its file descriptor, never copied whole: a
 * multi-gigabyte series would otherwise need twice the free space just to be inspected.
 * Audio is then extracted per chapter into app storage, because a DEFLATED archive entry
 * is not seekable and an audiobook player without working seek is useless.
 */
class ZipImporter(context: Context, private val store: LibraryStore) {

    private val appContext = context.applicationContext

    /** Reads the archive structure without writing anything. */
    suspend fun preview(uri: Uri): Result<ImportPreview> = withContext(Dispatchers.IO) {
        runCatching {
            val name = displayName(uri)
            openSource(uri).use { source ->
                val library = ZipReader.readLibrary(name, source)
                require(library.books.isNotEmpty()) {
                    val skippedNote = library.skipped.take(3).joinToString { it.reason }
                    if (library.skipped.isEmpty()) {
                        "В архиве нет аудиофайлов"
                    } else {
                        "В архиве нет аудиофайлов (пропущено: $skippedNote)"
                    }
                }
                ImportPreview(name, library)
            }
        }
    }

    /**
     * Extracts every chapter of every detected book and registers them in the store.
     * Already-imported archives are not de-duplicated by content; each import is a new
     * entry, so re-importing after a delete is always possible.
     */
    suspend fun import(
        uri: Uri,
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            val archiveName = displayName(uri)
            openSource(uri).use { source ->
                val rawEntries = ZipReader.listEntries(source)
                val byPath = rawEntries.associateBy { it.name.trimStart('/') }
                val library = LibraryBuilder.build(
                    archiveName,
                    rawEntries.filterNot { it.isDirectory }.map {
                        com.kingwar.zipbook.core.AudioEntry(
                            path = it.name.trimStart('/'),
                            sizeBytes = it.uncompressedSize,
                            compressedSizeBytes = it.compressedSize,
                            stored = it.stored,
                        )
                    },
                )
                if (library.books.isEmpty()) {
                    return@withContext ImportResult.Failure("В архиве нет аудиофайлов")
                }

                val total = library.chapterCount
                var done = 0
                val stored = mutableListOf<StoredBook>()

                for (book in library.books) {
                    val bookId = UUID.randomUUID().toString()
                    val bookDir = java.io.File(store.booksDir, bookId).apply { mkdirs() }
                    val chapters = mutableListOf<StoredChapter>()

                    // Filled from the first chapters whose ID3 tag carries text, used
                    // below to recover a real book title/author when the archive gave
                    // the parser nothing but numbered filenames in an unnamed folder.
                    var tagAlbum: String? = null
                    var tagArtist: String? = null

                    for (chapter in book.chapters) {
                        val raw = byPath[chapter.entry.path]
                            ?: return@withContext ImportResult.Failure(
                                "Запись не найдена в архиве: ${chapter.entry.path}",
                            )

                        // Extracted names are index-prefixed and sanitised: the original
                        // name may contain characters the filesystem rejects, and the
                        // index guarantees on-disk order matches playback order.
                        val safeName = "%05d_%s".format(chapter.index, sanitize(chapter.entry.fileName))
                        val outFile = java.io.File(bookDir, safeName)

                        ZipReader.openEntry(source, raw).use { input ->
                            FileOutputStream(outFile).use { output ->
                                input.copyTo(output, DEFAULT_BUFFER)
                            }
                        }

                        if (outFile.length() == 0L) {
                            outFile.delete()
                        } else {
                            // The filename parser already produced a title ("N. Clean
                            // name" or the generic "Глава N" when the name carried no
                            // usable text). ID3 TIT2 only overrides the generic case —
                            // a title the parser recovered from the archive wins.
                            val tags = if (chapters.size < TAG_PROBE_CHAPTERS || isGenericTitle(chapter.title, chapter.number)) {
                                runCatching { outFile.inputStream().buffered().use { Id3TagReader.extract(it) } }.getOrNull()
                            } else {
                                null
                            }
                            if (tagAlbum == null) tagAlbum = tags?.album
                            if (tagArtist == null) tagArtist = tags?.artist

                            val title = tags?.title?.takeIf { it.isNotBlank() }?.let {
                                if (isGenericTitle(chapter.title, chapter.number)) "${chapter.number}. $it" else chapter.title
                            } ?: chapter.title

                            chapters += StoredChapter(
                                index = chapters.size,
                                number = chapter.number,
                                title = title,
                                relativePath = "$bookId/$safeName",
                                sizeBytes = outFile.length(),
                                archivePath = chapter.entry.path,
                            )
                        }

                        done++
                        onProgress(
                            ImportProgress(
                                fraction = done.toFloat() / total,
                                currentFile = chapter.entry.fileName,
                                filesDone = done,
                                filesTotal = total,
                            ),
                        )
                    }

                    if (chapters.isEmpty()) {
                        bookDir.deleteRecursively()
                        continue
                    }

                    // Cover: an image inside the archive wins, because it is usually the
                    // full-size scan; art embedded in the first chapter is the fallback,
                    // and is the only source for archives that ship no image at all.
                    val cover = resolveCover(source, byPath, book, bookId, bookDir, chapters)

                    // Same rule as chapters: a title the parser recovered from a real
                    // directory/file name wins; the ID3 album tag only steps in when the
                    // archive gave nothing but digits to work with.
                    val title = if (isGenericTitle(book.title, null) && !tagAlbum.isNullOrBlank()) tagAlbum!! else book.title

                    stored += StoredBook(
                        id = bookId,
                        title = title,
                        series = book.series,
                        seriesIndex = book.seriesIndex,
                        chapters = chapters,
                        archiveName = archiveName,
                        layout = library.layout.name,
                        importedAtEpochMs = System.currentTimeMillis(),
                        coverPath = cover?.first,
                        coverSource = cover?.second,
                        author = tagArtist?.takeIf { it.isNotBlank() },
                    )
                }

                if (stored.isEmpty()) {
                    return@withContext ImportResult.Failure("Не удалось извлечь ни одной главы")
                }

                store.addBooks(stored)
                ImportResult.Success(stored)
            }
        } catch (e: ZipReader.NotAZipException) {
            ImportResult.Failure("Это не ZIP-архив: ${e.message}")
        } catch (e: Exception) {
            ImportResult.Failure(e.message ?: e::class.simpleName ?: "Неизвестная ошибка")
        }
    }

    /**
     * Resolves the cover for one book and writes it next to its chapters.
     *
     * @return (relative path, source label) or null when no cover could be found
     */
    private fun resolveCover(
        source: ZipSource,
        byPath: Map<String, ZipReader.RawEntry>,
        book: com.kingwar.zipbook.core.Book,
        bookId: String,
        bookDir: java.io.File,
        chapters: List<StoredChapter>,
    ): Pair<String, String>? {
        // 1. An image file inside the archive, already chosen by CoverFinder.
        book.coverEntryPath?.let { path ->
            val raw = byPath[path]
            if (raw != null && raw.uncompressedSize in 1..MAX_COVER_BYTES) {
                val bytes = runCatching {
                    ZipReader.openEntry(source, raw).use { it.readBytes() }
                }.getOrNull()
                if (bytes != null && bytes.isNotEmpty()) {
                    val ext = ImageSniffer.extensionOf(bytes)
                        ?: path.substringAfterLast('.', "jpg").lowercase()
                    val out = java.io.File(bookDir, "cover.$ext")
                    out.writeBytes(bytes)
                    return "$bookId/cover.$ext" to "archive"
                }
            }
        }

        // 2. Art embedded in the audio itself. The first chapter is the usual carrier,
        //    but some rips tag only a later file, so a few are tried before giving up.
        for (chapter in chapters.take(COVER_PROBE_CHAPTERS)) {
            val audio = java.io.File(store.booksDir, chapter.relativePath)
            if (!audio.isFile) continue
            val embedded = runCatching {
                audio.inputStream().buffered().use { Id3CoverExtractor.extract(it) }
            }.getOrNull() ?: continue
            if (embedded.bytes.size.toLong() > MAX_COVER_BYTES) continue
            val out = java.io.File(bookDir, "cover.${embedded.extension}")
            out.writeBytes(embedded.bytes)
            return "$bookId/cover.${embedded.extension}" to "id3"
        }

        return null
    }

    private fun openSource(uri: Uri): ZipSource {
        val pfd = appContext.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("Не удалось открыть файл")
        val channel = FileInputStream(pfd.fileDescriptor).channel
        return object : ZipSource {
            private val delegate = ZipSource.of(channel)
            override val size: Long get() = delegate.size
            override fun readFully(position: Long, buffer: ByteArray, offset: Int, length: Int) =
                delegate.readFully(position, buffer, offset, length)

            override fun openRange(position: Long, length: Long) = delegate.openRange(position, length)

            override fun close() {
                delegate.close()
                pfd.close()
            }
        }
    }

    private fun displayName(uri: Uri): String {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    cursor.getString(0)?.let { return it }
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "archive.zip"
    }

    private fun sanitize(name: String): String =
        name.map { ch ->
            when {
                ch.isLetterOrDigit() -> ch
                ch in ALLOWED_PUNCT -> ch
                else -> '_'
            }
        }.joinToString("").take(80).ifBlank { "track" }

    /**
     * True when [title] is what the filename parser produces when it found no usable
     * text — either the bare "Глава N" fallback, or a title made only of digits and
     * separators (an un-parsed numeric filename/directory such as "0001" or "Tom_02").
     */
    private fun isGenericTitle(title: String, number: Int?): Boolean {
        if (number != null && title == "Глава $number") return true
        return title.isBlank() || title.all { !it.isLetter() }
    }

    private companion object {
        const val DEFAULT_BUFFER = 128 * 1024
        const val MAX_COVER_BYTES = 12L * 1024 * 1024
        const val COVER_PROBE_CHAPTERS = 3
        const val TAG_PROBE_CHAPTERS = 3
        val ALLOWED_PUNCT = setOf('.', '-', '_', '(', ')', ' ')
    }
}
