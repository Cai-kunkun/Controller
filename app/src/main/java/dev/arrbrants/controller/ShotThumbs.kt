package dev.arrbrants.controller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache

/** Small in-memory cache for screenshot thumbnails shown inside chat bubbles. */
object ShotThumbs {

    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(context: Context, path: String): Bitmap? {
        cache.get(path)?.let { return it }
        val file = ScreenCapture.fileFor(context, path)
        if (!file.exists()) return null
        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        cache.put(path, bitmap)
        return bitmap
    }
}
