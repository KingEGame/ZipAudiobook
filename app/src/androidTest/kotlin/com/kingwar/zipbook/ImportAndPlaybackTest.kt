package com.kingwar.zipbook

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kingwar.zipbook.data.ImportResult
import com.kingwar.zipbook.data.LibraryStore
import com.kingwar.zipbook.data.StoredBook
import com.kingwar.zipbook.data.ZipImporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * On-device verification of the whole chain: a ZIP whose entry names are CP866-encoded
 * (no UTF-8 flag) is imported, grouped into a series, extracted, and then actually
 * decoded by the device's audio pipeline.
 *
 * This is the part a JVM test cannot prove: that the extracted bytes are playable by
 * ExoPlayer on real Android hardware, not merely byte-identical to the input.
 */
@RunWith(AndroidJUnit4::class)
class ImportAndPlaybackTest {

    private lateinit var context: Context
    private lateinit var store: LibraryStore
    private lateinit var importer: ZipImporter
    private lateinit var toneMp3: ByteArray
    private lateinit var taggedMp3: ByteArray

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        store = LibraryStore(context)
        importer = ZipImporter(context, store)
        toneMp3 = InstrumentationRegistry.getInstrumentation().context.assets
            .open("tone.mp3").use { it.readBytes() }
        assertTrue("fixture mp3 must be non-trivial", toneMp3.size > 5_000)
        taggedMp3 = InstrumentationRegistry.getInstrumentation().context.assets
            .open("tone_with_cover.mp3").use { it.readBytes() }
        assertTrue("tagged fixture must be non-trivial", taggedMp3.size > 20_000)

        // Start from a clean library so assertions are about this import only.
        runBlocking {
            store.load()
            store.state.value.books.forEach { store.removeBook(it.id) }
        }
    }

    @Test
    fun cp866_series_archive_imports_groups_and_plays(): Unit = runBlocking {
        val zip = File(context.cacheDir, "series_cp866_test.zip")
        writeZip(
            file = zip,
            charset = Charset.forName("CP866"),
            setUtf8Flag = false,
            entries = buildList {
                // Volumes deliberately written out of order, with mixed-width chapters.
                for (vol in listOf("02", "01", "10")) {
                    add("Лесовик/Том $vol/0002.mp3" to toneMp3)
                    add("Лесовик/Том $vol/0001.mp3" to toneMp3)
                }
                add("Лесовик/cover.jpg" to byteArrayOf(1, 2, 3))
            },
        )
        assertTrue("archive must exist on device", zip.length() > 10_000)

        // --- import ---
        val result = importer.import(Uri.fromFile(zip))
        assertTrue("import failed: $result", result is ImportResult.Success)
        val books = (result as ImportResult.Success).books

        // --- grouping ---
        assertEquals("three volumes expected", 3, books.size)
        assertTrue("series name must survive CP866 decoding", books.all { it.series == "Лесовик" })
        assertEquals(
            "volumes must be ordered 1, 2, 10 — not 1, 10, 2",
            listOf(1, 2, 10),
            books.map { it.seriesIndex },
        )
        books.forEach { book ->
            assertEquals("each volume has two chapters", 2, book.chapters.size)
            assertEquals(
                "chapters ordered by number",
                listOf(1, 2),
                book.chapters.map { it.number },
            )
            assertTrue(
                "no mojibake in titles: ${book.title}",
                book.title.none { it == '\uFFFD' },
            )
        }

        // --- extracted files really exist on disk ---
        val first = books.first()
        val chapterFiles = first.chapters.map { File(store.booksDir, it.relativePath) }
        chapterFiles.forEach { f ->
            assertTrue("extracted file missing: $f", f.isFile)
            assertEquals("extracted size must match the fixture", toneMp3.size.toLong(), f.length())
        }

        // --- actual decode + playback on device hardware ---
        val played = playAndMeasure(chapterFiles.first())
        assertTrue(
            "ExoPlayer reported duration ${played.durationMs}ms; expected ~3000ms",
            played.durationMs in 2_500..3_500,
        )
        assertTrue(
            "playback position did not advance (got ${played.positionMs}ms)",
            played.positionMs > 300,
        )

        // --- progress persistence survives a fresh store instance ---
        store.saveProgress(first.id, 1, 1_234L)
        val reopened = LibraryStore(context).also { it.load() }
        val saved = reopened.progressFor(first.id)
        assertEquals("chapter index must persist", 1, saved?.chapterIndex)
        assertEquals("position must persist", 1_234L, saved?.positionMs)
        assertTrue(
            "book must persist across store instances",
            reopened.bookById(first.id) != null,
        )

        cleanUp(books)
        zip.delete()
    }

    @Test
    fun cover_is_taken_from_the_track_tag_when_the_archive_has_no_image(): Unit = runBlocking {
        val zip = File(context.cacheDir, "id3_cover_test.zip")
        writeZip(
            file = zip,
            charset = Charset.forName("CP866"),
            setUtf8Flag = false,
            entries = listOf(
                "Книга с тегом/0001.mp3" to taggedMp3,
                "Книга с тегом/0002.mp3" to taggedMp3,
            ),
        )

        val result = importer.import(Uri.fromFile(zip))
        assertTrue("import failed: $result", result is ImportResult.Success)
        val book = (result as ImportResult.Success).books.single()

        assertEquals("cover must come from the ID3 tag", "id3", book.coverSource)
        val cover = File(store.booksDir, book.coverPath!!)
        assertTrue("cover file missing on disk", cover.isFile)
        assertTrue("cover is suspiciously small: ${cover.length()}", cover.length() > 15_000)
        // PNG magic: the art embedded in the fixture is a PNG.
        val magic = cover.readBytes().take(4)
        assertEquals(
            listOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()),
            magic,
        )

        cleanUp(listOf(book))
        zip.delete()
    }

    @Test
    fun an_image_in_the_archive_wins_over_the_track_tag(): Unit = runBlocking {
        // Both sources are present and have different byte lengths, so the winner is
        // identifiable from the extracted file alone.
        val archiveImage = ByteArray(9_001).also {
            it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte()
        }
        val zip = File(context.cacheDir, "both_covers_test.zip")
        writeZip(
            file = zip,
            charset = Charset.forName("CP866"),
            setUtf8Flag = false,
            entries = listOf(
                "Книга/обложка.jpg" to archiveImage,
                "Книга/0001.mp3" to taggedMp3,
            ),
        )

        val result = importer.import(Uri.fromFile(zip))
        assertTrue("import failed: $result", result is ImportResult.Success)
        val book = (result as ImportResult.Success).books.single()

        assertEquals("archive", book.coverSource)
        val cover = File(store.booksDir, book.coverPath!!)
        assertEquals(
            "the archive image must win, not the embedded one",
            archiveImage.size.toLong(),
            cover.length(),
        )

        cleanUp(listOf(book))
        zip.delete()
    }

    @Test
    fun no_cover_anywhere_leaves_the_fields_null(): Unit = runBlocking {
        val zip = File(context.cacheDir, "no_cover_test.zip")
        writeZip(
            file = zip,
            charset = Charsets.UTF_8,
            setUtf8Flag = true,
            entries = listOf("0001.mp3" to toneMp3),
        )
        val result = importer.import(Uri.fromFile(zip))
        val book = (result as ImportResult.Success).books.single()
        assertEquals(null, book.coverPath)
        assertEquals(null, book.coverSource)
        cleanUp(listOf(book))
        zip.delete()
    }

    private suspend fun cleanUp(books: List<StoredBook>) {
        books.forEach { store.removeBook(it.id) }
    }

    private data class Playback(val durationMs: Long, val positionMs: Long)

    /** Plays a file for ~1.2s on the main thread and reports duration + progress. */
    private fun playAndMeasure(file: File): Playback {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var player: ExoPlayer? = null
        val ready = CountDownLatch(1)
        var failure: Throwable? = null

        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) ready.countDown()
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        failure = error
                        ready.countDown()
                    }
                })
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                prepare()
                play()
            }
        }

        val becameReady = ready.await(15, TimeUnit.SECONDS)
        failure?.let { throw AssertionError("ExoPlayer failed to decode the extracted file", it) }
        assertTrue("ExoPlayer never reached STATE_READY", becameReady)

        Thread.sleep(1_200)

        var duration = 0L
        var position = 0L
        instrumentation.runOnMainSync {
            duration = player?.duration ?: 0L
            position = player?.currentPosition ?: 0L
            player?.release()
        }
        return Playback(duration, position)
    }

    // --- minimal ZIP writer with explicit name charset and flag control -------

    private fun writeZip(
        file: File,
        charset: Charset,
        setUtf8Flag: Boolean,
        entries: List<Pair<String, ByteArray>>,
    ) {
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        var count = 0

        for ((name, data) in entries) {
            val nameBytes = name.toByteArray(charset)
            val crc = CRC32().apply { update(data) }.value
            val payload = deflateRaw(data)
            val flags = if (setUtf8Flag) 0x0800 else 0
            val localOffset = out.size()

            out.writeIntLE(0x04034b50); out.writeShortLE(20); out.writeShortLE(flags)
            out.writeShortLE(8); out.writeShortLE(0); out.writeShortLE(0)
            out.writeIntLE(crc.toInt()); out.writeIntLE(payload.size); out.writeIntLE(data.size)
            out.writeShortLE(nameBytes.size); out.writeShortLE(0)
            out.write(nameBytes); out.write(payload)

            central.writeIntLE(0x02014b50); central.writeShortLE(20); central.writeShortLE(20)
            central.writeShortLE(flags); central.writeShortLE(8)
            central.writeShortLE(0); central.writeShortLE(0)
            central.writeIntLE(crc.toInt()); central.writeIntLE(payload.size); central.writeIntLE(data.size)
            central.writeShortLE(nameBytes.size); central.writeShortLE(0); central.writeShortLE(0)
            central.writeShortLE(0); central.writeShortLE(0); central.writeIntLE(0)
            central.writeIntLE(localOffset)
            central.write(nameBytes)
            count++
        }

        val cdOffset = out.size()
        val cd = central.toByteArray()
        out.write(cd)
        out.writeIntLE(0x06054b50); out.writeShortLE(0); out.writeShortLE(0)
        out.writeShortLE(count); out.writeShortLE(count)
        out.writeIntLE(cd.size); out.writeIntLE(cdOffset); out.writeShortLE(0)

        file.writeBytes(out.toByteArray())
    }

    private fun deflateRaw(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        d.setInput(data); d.finish()
        val buf = ByteArray(data.size + 1024)
        val n = d.deflate(buf)
        d.end()
        return buf.copyOf(n)
    }

    private fun ByteArrayOutputStream.writeShortLE(v: Int) {
        write(v and 0xFF); write((v ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeIntLE(v: Int) {
        write(v and 0xFF); write((v ushr 8) and 0xFF)
        write((v ushr 16) and 0xFF); write((v ushr 24) and 0xFF)
    }
}
