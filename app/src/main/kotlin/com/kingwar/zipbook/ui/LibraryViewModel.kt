package com.kingwar.zipbook.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingwar.zipbook.data.ImportProgress
import com.kingwar.zipbook.data.ImportResult
import com.kingwar.zipbook.data.Bookmark
import com.kingwar.zipbook.data.LibraryStore
import com.kingwar.zipbook.data.LibraryView
import com.kingwar.zipbook.data.StoredBook
import com.kingwar.zipbook.data.ZipImporter
import com.kingwar.zipbook.data.toLibraryView
import com.kingwar.zipbook.player.PlayerController
import com.kingwar.zipbook.player.PlayerUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Which screen is on top; kept as state so the app needs no navigation dependency. */
sealed interface Screen {
    data object Library : Screen
    data class BookDetail(val bookId: String) : Screen
    data class Series(val name: String) : Screen
    data object Player : Screen
    data object Settings : Screen
}

/** Transient import feedback shown as a banner or dialog. */
sealed interface ImportState {
    data object Idle : ImportState
    data class Working(val progress: ImportProgress?) : ImportState
    data class Done(val books: List<StoredBook>) : ImportState
    data class Error(val message: String) : ImportState
}

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    val store = LibraryStore(app)
    private val importer = ZipImporter(app, store)
    val player = PlayerController(app, store)

    val library: StateFlow<LibraryView> = store.state
        .map { it.books.toLibraryView() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LibraryView(series = emptyList(), standalone = emptyList()))

    private val _screen = MutableStateFlow<Screen>(Screen.Library)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    /** Epoch-ms the sleep timer will pause playback, or null when it is off. */
    private val _sleepUntil = MutableStateFlow<Long?>(null)
    val sleepUntil: StateFlow<Long?> = _sleepUntil.asStateFlow()
    private var sleepJob: Job? = null

    val playerState: StateFlow<PlayerUiState> = player.state

    init {
        viewModelScope.launch {
            store.load()
            player.connect()
        }
    }

    fun navigate(screen: Screen) {
        _screen.value = screen
    }

    fun back() {
        _screen.value = when (_screen.value) {
            is Screen.Player -> Screen.Library
            is Screen.BookDetail -> Screen.Library
            is Screen.Series -> Screen.Library
            is Screen.Settings -> Screen.Library
            Screen.Library -> Screen.Library
        }
    }

    fun importArchive(uri: Uri) {
        if (_importState.value is ImportState.Working) return
        _importState.value = ImportState.Working(null)
        viewModelScope.launch {
            val result = importer.import(uri) { progress ->
                _importState.value = ImportState.Working(progress)
            }
            _importState.value = when (result) {
                is ImportResult.Success -> ImportState.Done(result.books)
                is ImportResult.Failure -> ImportState.Error(result.message)
            }
        }
    }

    fun dismissImportState() {
        _importState.value = ImportState.Idle
    }

    fun bookById(id: String): StoredBook? = store.bookById(id)

    /** Starts a book from its saved position, or from the beginning if there is none. */
    fun playBook(book: StoredBook) {
        val progress = store.progressFor(book.id)
        val index = progress?.chapterIndex?.coerceIn(0, book.chapters.lastIndex) ?: 0
        val position = if (progress?.finished == true) 0L else progress?.positionMs ?: 0L
        player.play(book, index, position)
        _screen.value = Screen.Player
    }

    fun playChapter(book: StoredBook, chapterIndex: Int) {
        player.play(book, chapterIndex, 0L)
        _screen.value = Screen.Player
    }

    fun deleteBook(bookId: String) {
        viewModelScope.launch {
            store.removeBook(bookId)
            if ((_screen.value as? Screen.BookDetail)?.bookId == bookId) {
                _screen.value = Screen.Library
            }
        }
    }

    fun setSpeed(speed: Float) {
        player.setSpeed(speed)
        viewModelScope.launch { store.setPlaybackSpeed(speed) }
    }

    /** Pause playback after [minutes]; pass null to cancel a running timer. */
    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null || minutes <= 0) {
            _sleepUntil.value = null
            return
        }
        val durationMs = minutes * 60_000L
        _sleepUntil.value = System.currentTimeMillis() + durationMs
        sleepJob = viewModelScope.launch {
            delay(durationMs)
            player.pause()
            _sleepUntil.value = null
        }
    }

    fun addBookmarkAtCurrent() {
        val s = player.state.value
        val bookId = s.bookId ?: return
        val label = s.chapterTitle.ifBlank { "Глава ${s.chapterIndex + 1}" }
        viewModelScope.launch {
            store.addBookmark(
                Bookmark(
                    bookId = bookId,
                    chapterIndex = s.chapterIndex,
                    positionMs = s.positionMs,
                    label = label,
                    createdAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun removeBookmark(bookmark: Bookmark) {
        viewModelScope.launch { store.removeBookmark(bookmark) }
    }

    fun jumpToBookmark(bookmark: Bookmark) {
        player.seekToChapterAndPosition(bookmark.chapterIndex, bookmark.positionMs)
        _screen.value = Screen.Player
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}
