package com.kingwar.zipbook.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Single source of truth for the imported library and listening progress.
 *
 * State lives in one JSON file. Writes go through a temp file and an atomic rename, so
 * a process death mid-save cannot leave a half-written library behind — which would
 * otherwise mean losing every import.
 */
class LibraryStore(context: Context) {

    private val appContext = context.applicationContext
    private val stateFile = File(appContext.filesDir, "library.json")
    private val tempFile = File(appContext.filesDir, "library.json.tmp")

    /** Root directory holding extracted audio, one subdirectory per book. */
    val booksDir: File = File(appContext.filesDir, "books").apply { mkdirs() }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val writeMutex = Mutex()
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        if (!stateFile.exists()) return@withContext
        val loaded = runCatching {
            json.decodeFromString<LibraryState>(stateFile.readText())
        }.getOrElse {
            // A corrupt file must not brick the app; keep it aside for inspection.
            runCatching { stateFile.copyTo(File(appContext.filesDir, "library.corrupt.json"), overwrite = true) }
            LibraryState()
        }
        _state.value = loaded
    }

    private suspend fun persist(newState: LibraryState) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            _state.value = newState
            tempFile.writeText(json.encodeToString(newState))
            if (!tempFile.renameTo(stateFile)) {
                // renameTo can fail across some filesystems; fall back to a copy.
                tempFile.copyTo(stateFile, overwrite = true)
                tempFile.delete()
            }
        }
    }

    suspend fun addBooks(books: List<StoredBook>) {
        val current = _state.value
        val existingIds = current.books.map { it.id }.toSet()
        val merged = current.books + books.filterNot { it.id in existingIds }
        persist(current.copy(books = merged))
    }

    suspend fun removeBook(bookId: String) = withContext(Dispatchers.IO) {
        val current = _state.value
        val book = current.books.find { it.id == bookId }
        persist(
            current.copy(
                books = current.books.filterNot { it.id == bookId },
                progress = current.progress - bookId,
            ),
        )
        // Delete the extracted audio only after the state no longer references it.
        if (book != null) File(booksDir, book.id).deleteRecursively()
    }

    suspend fun saveProgress(bookId: String, chapterIndex: Int, positionMs: Long, finished: Boolean = false) {
        val current = _state.value
        val entry = Progress(
            bookId = bookId,
            chapterIndex = chapterIndex,
            positionMs = positionMs,
            updatedAtEpochMs = System.currentTimeMillis(),
            finished = finished,
        )
        persist(current.copy(progress = current.progress + (bookId to entry)))
    }

    suspend fun setPlaybackSpeed(speed: Float) {
        persist(_state.value.copy(playbackSpeed = speed.coerceIn(0.5f, 3.0f)))
    }

    suspend fun updateChapterDuration(bookId: String, chapterIndex: Int, durationMs: Long) {
        if (durationMs <= 0) return
        val current = _state.value
        val book = current.books.find { it.id == bookId } ?: return
        val chapter = book.chapters.getOrNull(chapterIndex) ?: return
        if (chapter.durationMs == durationMs) return
        val updatedBook = book.copy(
            chapters = book.chapters.map {
                if (it.index == chapterIndex) it.copy(durationMs = durationMs) else it
            },
        )
        persist(current.copy(books = current.books.map { if (it.id == bookId) updatedBook else it }))
    }

    fun progressFor(bookId: String): Progress? = _state.value.progress[bookId]

    suspend fun addBookmark(bookmark: Bookmark) {
        persist(_state.value.copy(bookmarks = _state.value.bookmarks + bookmark))
    }

    suspend fun removeBookmark(bookmark: Bookmark) {
        persist(_state.value.copy(bookmarks = _state.value.bookmarks - bookmark))
    }

    fun bookById(bookId: String): StoredBook? = _state.value.books.find { it.id == bookId }

    /** Absolute file for a chapter's extracted audio. */
    fun chapterFile(book: StoredBook, chapter: StoredChapter): File =
        File(booksDir, chapter.relativePath).let {
            if (it.isAbsolute) it else File(booksDir, chapter.relativePath)
        }

    /** Total bytes the app currently occupies with extracted audio. */
    suspend fun usedBytes(): Long = withContext(Dispatchers.IO) {
        booksDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
