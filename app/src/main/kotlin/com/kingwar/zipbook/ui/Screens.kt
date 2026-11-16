package com.kingwar.zipbook.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kingwar.zipbook.data.Bookmark
import com.kingwar.zipbook.data.Progress
import com.kingwar.zipbook.data.StoredBook
import java.io.File
import kotlinx.coroutines.delay

@Composable
fun ZipBookApp(
    viewModel: LibraryViewModel,
    onPickArchive: () -> Unit,
) {
    val screen by viewModel.screen.collectAsState()
    val importState by viewModel.importState.collectAsState()
    val playerState by viewModel.playerState.collectAsState()

    // One ticker for the whole app: keeps the mini-player and the player screen
    // in sync with the service while anything is loaded.
    LaunchedEffect(playerState.bookId, playerState.isPlaying) {
        if (playerState.bookId != null) {
            while (true) {
                viewModel.player.refresh()
                delay(500)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        BackHandler(enabled = screen != Screen.Library) { viewModel.back() }

        when (val s = screen) {
            Screen.Library -> LibraryScreen(viewModel, onPickArchive)
            is Screen.BookDetail -> BookDetailScreen(viewModel, s.bookId)
            is Screen.Series -> SeriesScreen(viewModel, s.name)
            Screen.Player -> PlayerScreen(viewModel)
            Screen.Settings -> SettingsScreen(viewModel)
        }

        // The persistent mini-player + navigation bar live above every screen
        // except the full player (the Yandex-Books pattern).
        if (screen != Screen.Player) {
            Column(Modifier.align(Alignment.BottomCenter)) {
                if (playerState.bookId != null) {
                    MiniPlayer(viewModel, Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                }
                BottomBar(
                    current = screen,
                    hasPlayback = playerState.bookId != null,
                    onLibrary = { viewModel.navigate(Screen.Library) },
                    onNowPlaying = { if (playerState.bookId != null) viewModel.navigate(Screen.Player) },
                    onImport = onPickArchive,
                    onMore = { viewModel.navigate(Screen.Settings) },
                )
            }
        }

        ImportOverlay(importState, viewModel::dismissImportState)
    }
}

// --- library -------------------------------------------------------------------

@Composable
private fun LibraryScreen(viewModel: LibraryViewModel, onPickArchive: () -> Unit) {
    val library by viewModel.library.collectAsState()
    val libState by viewModel.store.state.collectAsState()

    if (library.isEmpty) {
        EmptyLibrary(onPickArchive)
        return
    }

    val allBooks = remember(libState.books) { libState.books.sortedBy { it.displayTitle.lowercase() } }
    val continueItems = remember(libState.books, libState.progress) {
        libState.books.mapNotNull { book ->
            libState.progress[book.id]?.let { p -> if (!p.finished) book to p else null }
        }.sortedByDescending { it.second.updatedAtEpochMs }.take(6)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 150.dp),
    ) {
        item {
            Text("Моя библиотека", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
            Text(
                "${allBooks.size} ${plural(allBooks.size, "книга", "книги", "книг")} · " +
                    "${library.series.size} ${plural(library.series.size, "серия", "серии", "серий")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
            )
        }

        if (continueItems.isNotEmpty()) {
            item { SectionHeader("Продолжить") }
            items(continueItems, key = { "cont-${it.first.id}" }) { (book, progress) ->
                ContinueCard(viewModel, book, progress)
            }
        }

        if (library.series.isNotEmpty()) {
            item { SectionHeader("Серии") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
                    items(library.series, key = { "series-${it.name}" }) { group ->
                        val rep = group.volumes.firstOrNull { it.coverPath != null } ?: group.volumes.first()
                        SeriesCard(
                            viewModel = viewModel,
                            cover = rep,
                            name = group.name,
                            count = group.volumes.size,
                            onClick = { viewModel.navigate(Screen.Series(group.name)) },
                        )
                    }
                }
            }
        }

        item { SectionHeader("Все книги") }
        items(allBooks.chunked(3), key = { row -> "grid-${row.first().id}" }) { row ->
            BooksRow(viewModel, row, libState.progress)
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (action != null) {
            Text(
                action,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.then(if (onAction != null) Modifier.clickable { onAction() } else Modifier),
            )
        }
    }
}

@Composable
private fun ContinueCard(viewModel: LibraryViewModel, book: StoredBook, progress: Progress) {
    val fraction = progressFraction(book, progress)
    val subtitle = buildString {
        (book.author ?: book.series)?.let { append(it); append(" · ") }
        append("гл. ${progress.chapterIndex + 1} из ${book.chapters.size}")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { viewModel.navigate(Screen.BookDetail(book.id)) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(viewModel, book, size = 64.dp, cornerRadius = 12.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            GradientBar(fraction)
        }
        Spacer(Modifier.width(10.dp))
        CirclePlayButton(size = 40.dp, onClick = { viewModel.playBook(book) })
    }
}

@Composable
private fun GradientBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(4.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary),
                    ),
                ),
        )
    }
}

@Composable
private fun CirclePlayButton(size: Dp, playing: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (playing) "Пауза" else "Играть",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

@Composable
private fun SeriesCard(viewModel: LibraryViewModel, cover: StoredBook, name: String, count: Int, onClick: () -> Unit) {
    Column(Modifier.width(116.dp).clickable(onClick = onClick)) {
        Box {
            BookCover(viewModel, cover, size = 116.dp, cornerRadius = 14.dp)
            Box(
                Modifier
                    .padding(7.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    "$count ${plural(count, "том", "тома", "томов")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp),
        )
    }
}

@Composable
private fun BooksRow(viewModel: LibraryViewModel, books: List<StoredBook>, progress: Map<String, Progress>) {
    BoxWithConstraints {
        val gap = 12.dp
        val cell = (maxWidth - gap * 2) / 3
        Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
            books.forEach { book ->
                BookGridCard(viewModel, book, progressFraction(book, progress[book.id]), cell)
            }
            repeat(3 - books.size) { Spacer(Modifier.width(cell)) }
        }
    }
}

@Composable
private fun BookGridCard(viewModel: LibraryViewModel, book: StoredBook, fraction: Float, coverSize: Dp) {
    Column(Modifier.width(coverSize).clickable { viewModel.navigate(Screen.BookDetail(book.id)) }) {
        Box {
            BookCover(viewModel, book, size = coverSize, cornerRadius = 14.dp)
            if (fraction > 0f) {
                ProgressRing(fraction, Modifier.align(Alignment.BottomEnd).padding(7.dp))
            }
        }
        Text(
            book.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (!book.author.isNullOrBlank()) {
            Text(
                book.author!!,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProgressRing(fraction: Float, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier.size(22.dp).clip(CircleShape).background(Color(0xCC000000)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(15.dp)) {
            val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            drawArc(track, -90f, 360f, false, style = stroke)
            drawArc(accent, -90f, 360f * fraction.coerceIn(0f, 1f), false, style = stroke)
        }
    }
}

@Composable
private fun EmptyLibrary(onPickArchive: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Библиотека пуста", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Откройте ZIP-архив с аудиокнигой. Файлы вида 00.mp3, 0001.mp3 будут " +
                "упорядочены правильно, а папки «Том 01», «Том 02» распознаны как серия.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPickArchive) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Выбрать архив")
        }
    }
}

/**
 * Book cover, or a placeholder glyph when neither the archive nor the audio tags
 * carried an image. Decoded at the size it is drawn at, so a list never holds
 * full-resolution scans in memory.
 */
@Composable
private fun BookCover(
    viewModel: LibraryViewModel,
    book: StoredBook,
    size: Dp,
    cornerRadius: Dp = 6.dp,
) {
    val targetPx = with(LocalDensity.current) { size.roundToPx() }
    var image by remember(book.id, book.coverPath, targetPx) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(book.id, book.coverPath, targetPx) {
        val path = book.coverPath
        image = if (path == null) null
        else CoverLoader.load(File(viewModel.store.booksDir, path), targetPx)
    }

    val shape = RoundedCornerShape(cornerRadius)
    val current = image
    if (current != null) {
        Image(
            bitmap = current,
            contentDescription = "Обложка: ${book.displayTitle}",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(shape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.surface,
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                modifier = Modifier.size(size / 2),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- book detail ---------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookDetailScreen(viewModel: LibraryViewModel, bookId: String) {
    val state by viewModel.store.state.collectAsState()
    val playerState by viewModel.playerState.collectAsState()
    val book = state.books.find { it.id == bookId }
    var confirmDelete by remember { mutableStateOf(false) }

    if (book == null) {
        LaunchedEffect(Unit) { viewModel.back() }
        return
    }
    val progress = state.progress[bookId]
    val resumeLabel = if (progress != null && !progress.finished) "Продолжить · гл. ${progress.chapterIndex + 1}" else "Слушать"

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(book.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = viewModel::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Удалить")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 150.dp),
        ) {
            item {
                Row(Modifier.padding(top = 8.dp, bottom = 16.dp)) {
                    BookCover(viewModel, book, size = 104.dp, cornerRadius = 16.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(book.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, lineHeight = MaterialTheme.typography.titleLarge.fontSize)
                        if (!book.author.isNullOrBlank()) {
                            Text(book.author!!, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
                        }
                        FlowRowChips(
                            "${book.chapters.size} ${plural(book.chapters.size, "глава", "главы", "глав")}",
                            formatBytes(book.totalBytes),
                            "обложка: ${coverSourceLabel(book.coverSource)}",
                        )
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { viewModel.playBook(book) },
                        modifier = Modifier.weight(1f).height(46.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(resumeLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    FilledTonalButton(
                        onClick = { viewModel.playChapter(book, 0) },
                        modifier = Modifier.height(46.dp),
                    ) { Text("Сначала") }
                }
            }
            items(book.chapters, key = { it.index }) { chapter ->
                val isCurrent = playerState.bookId == book.id && playerState.chapterIndex == chapter.index
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.playChapter(book, chapter.index) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${chapter.number}",
                        modifier = Modifier.width(28.dp),
                        textAlign = TextAlign.Center,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            chapter.title,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isCurrent) {
                            Text("играет сейчас", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (chapter.durationMs > 0) {
                        Text(formatDuration(chapter.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить книгу?") },
            text = { Text("Извлечённые аудиофайлы будут удалены. Архив на устройстве останется.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteBook(book.id)
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun FlowRowChips(vararg labels: String) {
    Column {
        labels.forEach { label ->
            Text(
                "· $label",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

// --- series --------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeriesScreen(viewModel: LibraryViewModel, seriesName: String) {
    val library by viewModel.library.collectAsState()
    val libState by viewModel.store.state.collectAsState()
    val group = library.series.find { it.name == seriesName }

    if (group == null) {
        LaunchedEffect(Unit) { viewModel.back() }
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(seriesName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = viewModel::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 150.dp),
        ) {
            item {
                Text(
                    "${group.volumes.size} ${plural(group.volumes.size, "том", "тома", "томов")} · " +
                        "${group.chapterCount} ${plural(group.chapterCount, "глава", "главы", "глав")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            items(group.volumes, key = { it.id }) { book ->
                val progress = libState.progress[book.id]
                val label = book.seriesIndex?.let { "Том $it" }?.let {
                    if (book.title.isNotBlank() && book.title != book.series) "$it · ${book.title}" else it
                } ?: book.title
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.navigate(Screen.BookDetail(book.id)) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BookCover(viewModel, book, size = 56.dp, cornerRadius = 10.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${book.chapters.size} ${plural(book.chapters.size, "глава", "главы", "глав")} · ${formatBytes(book.totalBytes)}" +
                                when {
                                    progress?.finished == true -> " · прослушано"
                                    progress != null -> " · гл. ${progress.chapterIndex + 1}"
                                    else -> ""
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    CirclePlayButton(size = 40.dp, onClick = { viewModel.playBook(book) })
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

// --- player --------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerScreen(viewModel: LibraryViewModel) {
    val state by viewModel.playerState.collectAsState()
    val sleepUntil by viewModel.sleepUntil.collectAsState()
    val book = state.bookId?.let { viewModel.bookById(it) }

    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }

    if (state.bookId == null || book == null) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Сейчас ничего не играет", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = viewModel::back) { Text("К библиотеке") }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp).padding(top = 8.dp, bottom = 20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = viewModel::back) { Icon(Icons.Filled.ExpandMore, contentDescription = "Свернуть") }
            Text(book.displayTitle, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            IconButton(onClick = { viewModel.navigate(Screen.BookDetail(book.id)) }) { Icon(Icons.Filled.MoreVert, contentDescription = "Подробнее") }
        }

        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            BookCover(viewModel, book, size = 230.dp, cornerRadius = 22.dp)
        }
        Spacer(Modifier.height(22.dp))

        Text(book.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        if (!book.author.isNullOrBlank()) {
            Text(book.author!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 5.dp))
        }
        Text(
            "Глава ${state.chapterIndex + 1} · ${state.chapterTitle}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp),
        )

        val duration = state.durationMs.coerceAtLeast(1L)
        Slider(
            value = state.positionMs.coerceIn(0L, duration).toFloat(),
            onValueChange = { viewModel.player.seekTo(it.toLong()) },
            valueRange = 0f..duration.toFloat(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(state.positionMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("-" + formatDuration((state.durationMs - state.positionMs).coerceAtLeast(0L)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.player.seekBy(-10_000) }) { Icon(Icons.Filled.Replay10, contentDescription = "Назад 10 с", modifier = Modifier.size(28.dp)) }
            IconButton(onClick = viewModel.player::previousChapter) { Icon(Icons.Filled.SkipPrevious, contentDescription = "Предыдущая глава", modifier = Modifier.size(32.dp)) }
            CirclePlayButton(size = 72.dp, playing = state.isPlaying, onClick = viewModel.player::togglePlayPause)
            IconButton(onClick = viewModel.player::nextChapter) { Icon(Icons.Filled.SkipNext, contentDescription = "Следующая глава", modifier = Modifier.size(32.dp)) }
            IconButton(onClick = { viewModel.player.seekBy(30_000) }) { Icon(Icons.Filled.Forward30, contentDescription = "Вперёд 30 с", modifier = Modifier.size(28.dp)) }
        }

        Spacer(Modifier.weight(1f))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(14.dp))
        val sleepLabel = remember(sleepUntil, state.positionMs) {
            sleepUntil?.let { until ->
                val left = ((until - System.currentTimeMillis()) / 60_000L).toInt() + 1
                if (left > 0) "$left мин" else "Таймер"
            } ?: "Таймер"
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            PlayerTool(Icons.Filled.Speed, formatSpeed(state.speed), accentLabel = true) { showSpeed = true }
            PlayerTool(Icons.Filled.Bedtime, sleepLabel, accentLabel = sleepUntil != null) { showSleep = true }
            PlayerTool(Icons.AutoMirrored.Filled.List, "Главы") { showChapters = true }
            PlayerTool(Icons.Filled.BookmarkBorder, "Закл.") { showBookmarks = true }
        }
    }

    if (showSpeed) SpeedDialog(state.speed, onPick = { viewModel.setSpeed(it); showSpeed = false }, onDismiss = { showSpeed = false })
    if (showSleep) SleepDialog(sleepUntil != null, onPick = { viewModel.setSleepTimer(it); showSleep = false }, onDismiss = { showSleep = false })
    if (showChapters) {
        ChaptersSheet(book, state.chapterIndex, onPick = { viewModel.player.seekToChapter(it); showChapters = false }, onDismiss = { showChapters = false })
    }
    if (showBookmarks) {
        BookmarksSheet(viewModel, book.id, onDismiss = { showBookmarks = false })
    }
}

@Composable
private fun PlayerTool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, accentLabel: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (accentLabel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (accentLabel) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun SpeedDialog(current: Float, onPick: (Float) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Скорость") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f).chunked(3).forEach { rowSpeeds ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowSpeeds.forEach { speed ->
                            FilterChip(
                                selected = kotlin.math.abs(current - speed) < 0.01f,
                                onClick = { onPick(speed) },
                                label = { Text(formatSpeed(speed)) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
}

@Composable
private fun SleepDialog(active: Boolean, onPick: (Int?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Таймер сна") },
        text = {
            Column {
                listOf(15, 30, 45, 60).forEach { minutes ->
                    TextButton(onClick = { onPick(minutes) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Через $minutes минут", modifier = Modifier.fillMaxWidth())
                    }
                }
                if (active) {
                    TextButton(onClick = { onPick(null) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Выключить таймер", color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChaptersSheet(book: StoredBook, currentIndex: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Text("Главы", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(book.chapters, key = { it.index }) { chapter ->
                val isCurrent = chapter.index == currentIndex
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(chapter.index) }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${chapter.number}", modifier = Modifier.width(28.dp), color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(chapter.title, Modifier.weight(1f), color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (chapter.durationMs > 0) Text(formatDuration(chapter.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarksSheet(viewModel: LibraryViewModel, bookId: String, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val libState by viewModel.store.state.collectAsState()
    val bookmarks = libState.bookmarks.filter { it.bookId == bookId }
        .sortedWith(compareBy({ it.chapterIndex }, { it.positionMs }))

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Закладки", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = { viewModel.addBookmarkAtCurrent() }) {
                Icon(Icons.Filled.Bookmark, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Добавить здесь")
            }
        }
        if (bookmarks.isEmpty()) {
            Text("Пока нет закладок", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(bookmarks, key = { it.createdAtEpochMs }) { bookmark ->
                    Row(
                        Modifier.fillMaxWidth().clickable { viewModel.jumpToBookmark(bookmark); onDismiss() }.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Гл. ${bookmark.chapterIndex + 1} · ${bookmark.label}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(formatDuration(bookmark.positionMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { viewModel.removeBookmark(bookmark) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Удалить закладку", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

// --- mini player + bottom bar --------------------------------------------------

@Composable
private fun MiniPlayer(viewModel: LibraryViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.playerState.collectAsState()
    val book = state.bookId?.let { viewModel.bookById(it) } ?: return
    val fraction = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f

    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { viewModel.navigate(Screen.Player) },
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp).padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(viewModel, book, size = 40.dp, cornerRadius = 10.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(state.chapterTitle.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(book.displayTitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            CirclePlayButton(size = 36.dp, playing = state.isPlaying, onClick = viewModel.player::togglePlayPause)
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth(fraction).height(2.dp).background(MaterialTheme.colorScheme.primary))
    }
}

@Composable
private fun BottomBar(
    current: Screen,
    hasPlayback: Boolean,
    onLibrary: () -> Unit,
    onNowPlaying: () -> Unit,
    onImport: () -> Unit,
    onMore: () -> Unit,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        NavigationBarItem(
            selected = current == Screen.Library,
            onClick = onLibrary,
            icon = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null) },
            label = { Text("Библиотека") },
        )
        NavigationBarItem(
            selected = false,
            enabled = hasPlayback,
            onClick = onNowPlaying,
            icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
            label = { Text("Сейчас") },
        )
        NavigationBarItem(
            selected = false,
            onClick = onImport,
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            label = { Text("Импорт") },
        )
        NavigationBarItem(
            selected = current == Screen.Settings,
            onClick = onMore,
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            label = { Text("Ещё") },
        )
    }
}

// --- settings ("Ещё") ----------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(viewModel: LibraryViewModel) {
    val state by viewModel.store.state.collectAsState()
    var usedBytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.books) { usedBytes = viewModel.store.usedBytes() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(title = { Text("Ещё") }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Скорость по умолчанию", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                    FilterChip(
                        selected = kotlin.math.abs(state.playbackSpeed - speed) < 0.01f,
                        onClick = { viewModel.setSpeed(speed) },
                        label = { Text(formatSpeed(speed)) },
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            InfoRow("Книг в библиотеке", "${state.books.size}")
            InfoRow("Закладок", "${state.bookmarks.size}")
            InfoRow("Занято на устройстве", usedBytes?.let { formatBytes(it) } ?: "…")
            InfoRow("Версия", "ZipBook 0.1.0")
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

// --- import feedback -----------------------------------------------------------

@Composable
private fun ImportOverlay(state: ImportState, onDismiss: () -> Unit) {
    when (state) {
        ImportState.Idle -> Unit

        is ImportState.Working -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Импорт архива") },
            text = {
                Column {
                    val p = state.progress
                    if (p == null) {
                        Text("Читаем структуру архива…")
                        Spacer(Modifier.height(12.dp))
                        CircularProgressIndicator()
                    } else {
                        Text("${p.filesDone} из ${p.filesTotal}: ${p.currentFile}", maxLines = 2)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { p.fraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {},
        )

        is ImportState.Done -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Готово") },
            text = {
                Column {
                    val chapters = state.books.sumOf { it.chapters.size }
                    Text("Добавлено книг: ${state.books.size}, глав: $chapters")
                    state.books.take(6).forEach { Text("• ${it.displayTitle} (${it.chapters.size})") }
                    if (state.books.size > 6) Text("…")
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("ОК") } },
        )

        is ImportState.Error -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Не удалось импортировать") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("ОК") } },
        )
    }
}

// --- helpers -------------------------------------------------------------------

private fun progressFraction(book: StoredBook, progress: Progress?): Float {
    if (progress == null) return 0f
    if (progress.finished) return 1f
    val count = book.chapters.size
    if (count == 0) return 0f
    val durations = book.chapters.map { it.durationMs }
    return if (durations.all { it > 0 }) {
        val total = durations.sum().toDouble()
        val before = durations.take(progress.chapterIndex.coerceIn(0, count)).sum().toDouble()
        ((before + progress.positionMs) / total).toFloat().coerceIn(0f, 1f)
    } else {
        ((progress.chapterIndex + 0.5f) / count).coerceIn(0f, 1f)
    }
}

private fun formatSpeed(speed: Float): String {
    val s = if (speed == speed.toLong().toFloat()) "%.1f".format(speed)
    else "%.2f".format(speed).trimEnd('0')
    return s + "×"
}

private fun plural(n: Int, one: String, few: String, many: String): String {
    val mod10 = n % 10
    val mod100 = n % 100
    return when {
        mod10 == 1 && mod100 != 11 -> one
        mod10 in 2..4 && mod100 !in 12..14 -> few
        else -> many
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f ГБ".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.0f МБ".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.0f КБ".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes Б"
}

internal fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

internal fun coverSourceLabel(source: String?): String = when (source) {
    "archive" -> "из архива"
    "id3" -> "из тега (ID3)"
    else -> "нет"
}

internal fun layoutLabel(layout: String): String = when (layout) {
    "FLAT_SINGLE_BOOK" -> "один уровень, одна книга"
    "DIRECTORY_PER_BOOK" -> "папка на книгу"
    "SERIES_WITH_VOLUMES" -> "серия с томами"
    "NESTED" -> "вложенные папки"
    "MIXED" -> "смешанная (есть файлы в корне)"
    else -> layout
}
