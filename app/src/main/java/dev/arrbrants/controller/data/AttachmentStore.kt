package dev.arrbrants.controller.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Copies picked files into app storage and turns them into prompt parts.
 * Images are bounded and re-encoded to JPEG; text files are inlined; anything
 * else is announced by name and type only (no binary payloads to the model).
 */
object AttachmentStore {

    private const val DIR = "attachments"
    private const val MAX_IMAGE_DIM = 1536
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024
    private const val MAX_TEXT_CHARS = 20_000
    private const val MAX_IMAGES_PER_MESSAGE = 4

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "json", "csv", "tsv", "log", "xml", "yaml", "yml",
        "ini", "conf", "html", "htm", "css", "js", "ts", "kt", "java", "py", "rb",
        "go", "rs", "c", "h", "cpp", "sh", "sql", "toml"
    )

    fun copyIn(context: Context, conversationId: Long, uri: Uri): Attachment? {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val displayName = queryName(context, uri)
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val stamp = System.currentTimeMillis()

        return try {
            if (mime.startsWith("image/")) {
                importImage(resolver, uri, dir, conversationId, stamp, displayName) ?:
                    importFile(resolver, uri, dir, conversationId, stamp, displayName, mime)
            } else {
                importFile(resolver, uri, dir, conversationId, stamp, displayName, mime)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun importImage(
        resolver: android.content.ContentResolver,
        uri: Uri,
        dir: File,
        conversationId: Long,
        stamp: Long,
        displayName: String
    ): Attachment? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_DIM) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val scaled = if (maxOf(bitmap.width, bitmap.height) > MAX_IMAGE_DIM) {
            val scale = MAX_IMAGE_DIM.toFloat() / maxOf(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }
        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()

        val file = File(dir, "${conversationId}_${stamp}_${safeName(displayName)}.jpg")
        file.writeBytes(bytes)
        return Attachment("$DIR/${file.name}", displayName, "image/jpeg", file.length())
    }

    private fun importFile(
        resolver: android.content.ContentResolver,
        uri: Uri,
        dir: File,
        conversationId: Long,
        stamp: Long,
        displayName: String,
        mime: String
    ): Attachment? {
        val file = File(dir, "${conversationId}_${stamp}_${safeName(displayName)}")
        var total = 0L
        resolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { out ->
                val buffer = ByteArray(8192)
                while (total < MAX_FILE_BYTES) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    total += read
                }
            }
        } ?: return null
        return Attachment("$DIR/${file.name}", displayName, mime, file.length())
    }

    /** Base64 data URLs for the images of one message. */
    fun imageDataUrls(context: Context, attachments: List<Attachment>): List<String> {
        val urls = ArrayList<String>()
        for (a in attachments) {
            if (!a.isImage || urls.size >= MAX_IMAGES_PER_MESSAGE) continue
            val file = File(context.filesDir, a.path)
            if (!file.exists()) continue
            val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            urls.add("data:image/jpeg;base64,$encoded")
        }
        return urls
    }

    /** Inline text for text-like attachments, or null for binary files. */
    fun readText(context: Context, attachment: Attachment): String? {
        val extension = attachment.name.substringAfterLast('.', "").lowercase()
        val textLike = attachment.mime.startsWith("text/") ||
            attachment.mime == "application/json" ||
            attachment.mime == "application/xml" ||
            extension in TEXT_EXTENSIONS
        if (!textLike) return null
        val file = File(context.filesDir, attachment.path)
        if (!file.exists()) return null
        return try {
            file.bufferedReader().use { reader ->
                val buffer = CharArray(MAX_TEXT_CHARS)
                val read = reader.read(buffer)
                if (read <= 0) "" else String(buffer, 0, read)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun delete(context: Context, attachments: List<Attachment>) {
        for (a in attachments) {
            File(context.filesDir, a.path).delete()
        }
    }

    private fun queryName(context: Context, uri: Uri): String {
        val fallback = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index) ?: fallback
                } else {
                    fallback
                }
            } ?: fallback
        } catch (e: Exception) {
            fallback
        }
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(60).ifBlank { "file" }
}
