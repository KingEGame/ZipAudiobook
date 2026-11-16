package com.kingwar.zipbook.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Loads cover images from app storage for Compose.
 *
 * Deliberately dependency-free (no Coil/Glide) because the need here is narrow: a few
 * local files, two display sizes. Doing it by hand also means the downsampling is
 * explicit — a 3000x3000 scan decoded at full size is ~36 MB of heap per cover, which
 * would make a library list of such books unusable.
 *
 * Bitmaps are keyed by file path *and* target size, so the list thumbnail and the big
 * player artwork are cached separately instead of fighting over one entry.
 */
object CoverLoader {

    /** Roughly 12 MB of decoded covers; the cache evicts by actual byte count. */
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Paths already known to have no usable image, so a broken file is tried only once. */
    private val failed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    suspend fun load(file: File, targetPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "${file.absolutePath}@$targetPx"
        cache.get(key)?.let { return@withContext it.asImageBitmap() }
        if (key in failed || !file.isFile || file.length() == 0L) return@withContext null

        val bitmap = decodeDownsampled(file, targetPx)
        if (bitmap == null) {
            failed += key
            return@withContext null
        }
        cache.put(key, bitmap)
        bitmap.asImageBitmap()
    }

    /** Clears cached entries for a book that was deleted or re-imported. */
    fun invalidate() {
        cache.evictAll()
        failed.clear()
    }

    private fun decodeDownsampled(file: File, targetPx: Int): Bitmap? {
        // Pass 1: read only the header to learn the real dimensions.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) } }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        // Pass 2: decode at the smallest power-of-two scale that still covers the target.
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(width, height, targetPx)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return runCatching {
            file.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
    }

    private fun sampleSizeFor(width: Int, height: Int, targetPx: Int): Int {
        if (targetPx <= 0) return 1
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= targetPx && h / 2 >= targetPx) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }
}
