package com.kingwar.zipbook.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.kingwar.zipbook.data.LibraryStore
import com.kingwar.zipbook.data.StoredBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** What the player UI renders, refreshed from controller callbacks and a ticker. */
data class PlayerUiState(
    val bookId: String? = null,
    val bookTitle: String = "",
    val chapterTitle: String = "",
    val chapterIndex: Int = 0,
    val chapterCount: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val speed: Float = 1.0f,
    val connected: Boolean = false,
)

/**
 * Owns the [MediaController] connection to [PlaybackService] and translates it into
 * state the Compose layer can render, plus the progress writes that make "resume where
 * I stopped" work across app restarts.
 */
class PlayerController(
    private val context: Context,
    private val store: LibraryStore,
) {

    private var controller: MediaController? = null
    private var currentBook: StoredBook? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** Last position written to disk, used to throttle saves to once per second. */
    private var lastSavedPositionMs = -1L

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refresh()
            if (!isPlaying) persistProgress(force = true)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            refresh()
            persistProgress(force = true)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            refresh()
            if (playbackState == Player.STATE_ENDED) {
                val book = currentBook
                if (book != null) {
                    saveProgressBlocking(book.id, book.chapters.lastIndex, 0L, finished = true)
                }
            }
        }
    }

    fun connect(onReady: () -> Unit = {}) {
        if (controller != null) {
            onReady()
            return
        }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()
            controller?.addListener(listener)
            controller?.setPlaybackSpeed(store.state.value.playbackSpeed)
            _state.value = _state.value.copy(connected = controller != null)
            // The service may already be playing a book from a previous process —
            // this activity/ViewModel instance never called play() for it, so
            // currentBook starts null. Recover it from the session's own media item
            // so the "now playing" bar and the player screen show up immediately
            // instead of only after the next user-triggered playback event.
            recoverCurrentBookIfNeeded()
            refresh()
            onReady()
        }, MoreExecutors.directExecutor())
    }

    fun release() {
        persistProgress(force = true)
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        _state.value = PlayerUiState()
    }

    /**
     * Loads a book and starts at [chapterIndex]/[positionMs]. Called both for a fresh
     * tap and for "continue", so resume is not a special case.
     */
    fun play(book: StoredBook, chapterIndex: Int, positionMs: Long) {
        val c = controller ?: run {
            connect { play(book, chapterIndex, positionMs) }
            return
        }
        currentBook = book

        // Attached once per item so the lock screen and the notification show the cover
        // the importer pulled out of the archive or the track's ID3 tag.
        val artworkUri = book.coverPath
            ?.let { File(store.booksDir, it) }
            ?.takeIf { it.isFile }
            ?.let { Uri.fromFile(it) }

        val items = book.chapters.map { chapter ->
            val file = File(store.booksDir, chapter.relativePath)
            MediaItem.Builder()
                .setUri(Uri.fromFile(file))
                .setMediaId("${book.id}:${chapter.index}")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(chapter.title)
                        .setArtist(book.displayTitle)
                        .setAlbumTitle(book.series ?: book.title)
                        .setTrackNumber(chapter.number)
                        .setArtworkUri(artworkUri)
                        .build(),
                )
                .build()
        }

        val safeIndex = chapterIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        c.setMediaItems(items, safeIndex, positionMs.coerceAtLeast(0L))
        c.setPlaybackSpeed(store.state.value.playbackSpeed)
        c.prepare()
        c.play()
        refresh()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
        refresh()
    }

    /** Explicit pause, used by the sleep timer. */
    fun pause() {
        controller?.pause()
        refresh()
    }

    /** Jump to a chapter and an offset within it — used when opening a bookmark. */
    fun seekToChapterAndPosition(index: Int, positionMs: Long) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) {
            c.seekTo(index, positionMs.coerceAtLeast(0L))
            c.play()
            refresh()
        }
    }

    fun seekToChapter(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) {
            c.seekTo(index, 0L)
            c.play()
            refresh()
        }
    }

    fun nextChapter() = controller?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem(); refresh() }

    fun previousChapter() = controller?.let {
        // Standard player behaviour: restart the chapter unless we are near its start.
        if (it.currentPosition > 3_000L) it.seekTo(0L) else if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem()
        refresh()
    }

    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        val target = (c.currentPosition + deltaMs).coerceAtLeast(0L)
        val duration = c.duration
        c.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
        refresh()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
        refresh()
    }

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        controller?.setPlaybackSpeed(clamped)
        _state.value = _state.value.copy(speed = clamped)
    }

    /** Called from a UI ticker while the player screen is visible. */
    fun refresh() {
        val c = controller
        if (c == null) {
            _state.value = _state.value.copy(connected = false, isPlaying = false)
            return
        }
        recoverCurrentBookIfNeeded()
        val book = currentBook
        val index = c.currentMediaItemIndex
        val chapter = book?.chapters?.getOrNull(index)
        _state.value = PlayerUiState(
            bookId = book?.id,
            bookTitle = book?.displayTitle.orEmpty(),
            chapterTitle = chapter?.title ?: c.mediaMetadata.title?.toString().orEmpty(),
            chapterIndex = index,
            chapterCount = c.mediaItemCount,
            positionMs = c.currentPosition.coerceAtLeast(0L),
            durationMs = c.duration.takeIf { it > 0 } ?: (chapter?.durationMs ?: 0L),
            isPlaying = c.isPlaying,
            speed = c.playbackParameters.speed,
            connected = true,
        )
        persistProgress(force = false)
    }

    /**
     * Recovers [currentBook] from the controller's current media item when this
     * instance never called [play] itself — e.g. the app process restarted while the
     * playback service kept running. Media ids are "bookId:chapterIndex" (see [play]),
     * so the book id alone is enough to look it up in the store.
     */
    private fun recoverCurrentBookIfNeeded() {
        if (currentBook != null) return
        val c = controller ?: return
        val mediaId = c.currentMediaItem?.mediaId ?: return
        val bookId = mediaId.substringBefore(':', missingDelimiterValue = "")
        if (bookId.isNotBlank()) currentBook = store.bookById(bookId)
    }

    private fun persistProgress(force: Boolean) {
        val c = controller ?: return
        val book = currentBook ?: return
        val position = c.currentPosition
        if (!force && lastSavedPositionMs >= 0 && kotlin.math.abs(position - lastSavedPositionMs) < 1_000L) return
        lastSavedPositionMs = position
        val index = c.currentMediaItemIndex
        scope.launch { store.saveProgress(book.id, index, position, finished = false) }

        val duration = c.duration
        if (duration > 0) {
            scope.launch { store.updateChapterDuration(book.id, index, duration) }
        }
    }

    private fun saveProgressBlocking(bookId: String, chapterIndex: Int, positionMs: Long, finished: Boolean) {
        scope.launch { store.saveProgress(bookId, chapterIndex, positionMs, finished) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}
