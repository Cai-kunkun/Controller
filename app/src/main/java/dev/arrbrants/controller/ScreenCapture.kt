package dev.arrbrants.controller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Captures screenshots through root (`screencap`) and keeps a downscaled
 * JPEG around for the vision request and the chat history.
 */
object ScreenCapture {

    private const val MAX_DIM = 1536
    private const val QUALITY = 80
    private const val SHOTS_DIR = "shots"

    data class Shot(
        val jpeg: ByteArray,
        val screenWidth: Int,
        val screenHeight: Int,
        val sentWidth: Int,
        val sentHeight: Int
    )

    fun capture(context: Context): Shot {
        val tmp = File(context.cacheDir, "screencap.png")
        tmp.delete()
        val res = RootShell.exec("screencap -p ${RootShell.shellQuote(tmp.absolutePath)}", 15000)
        if (!tmp.exists() || tmp.length() == 0L) {
            throw IOException("screencap failed: ${res.stderr.ifBlank { res.stdout }}")
        }
        val full = BitmapFactory.decodeFile(tmp.absolutePath)
            ?: throw IOException("Could not decode screenshot")
        tmp.delete()

        val screenWidth = full.width
        val screenHeight = full.height
        val scale = MAX_DIM.toFloat() / max(screenWidth, screenHeight)
        val sent = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                full,
                (screenWidth * scale).roundToInt(),
                (screenHeight * scale).roundToInt(),
                true
            )
        } else {
            full
        }
        val annotated = sent.copy(Bitmap.Config.ARGB_8888, true) ?: sent
        drawGrid(annotated)
        val jpeg = ByteArrayOutputStream().use { baos ->
            annotated.compress(Bitmap.CompressFormat.JPEG, QUALITY, baos)
            baos.toByteArray()
        }
        val shot = Shot(jpeg, screenWidth, screenHeight, sent.width, sent.height)
        if (annotated !== sent) annotated.recycle()
        if (sent !== full) sent.recycle()
        full.recycle()
        return shot
    }

    /** Faint 0-1000 coordinate grid so the model can read positions off the image. */
    private fun drawGrid(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        val canvas = Canvas(bitmap)
        val linePaint = Paint().apply {
            color = 0x55FF00FF.toInt()
            strokeWidth = max(1f, w * 0.002f)
            style = Paint.Style.STROKE
        }
        val textPaint = Paint().apply {
            color = 0x99FF00FF.toInt()
            textSize = w * 0.022f
            isAntiAlias = true
        }
        for (i in 1..9) {
            val x = w * i / 10f
            val y = h * i / 10f
            canvas.drawLine(x, 0f, x, h.toFloat(), linePaint)
            canvas.drawLine(0f, y, w.toFloat(), y, linePaint)
            canvas.drawText((i * 100).toString(), x + 3f, textPaint.textSize, textPaint)
            canvas.drawText((i * 100).toString(), 3f, y - 3f, textPaint)
        }
    }

    /** Persists the JPEG in app storage and returns the path relative to filesDir. */
    fun saveShot(context: Context, conversationId: Long, jpeg: ByteArray): String {
        val dir = File(context.filesDir, SHOTS_DIR)
        dir.mkdirs()
        val file = File(dir, "${conversationId}_${System.currentTimeMillis()}.jpg")
        file.writeBytes(jpeg)
        return "$SHOTS_DIR/${file.name}"
    }

    fun fileFor(context: Context, relativePath: String): File = File(context.filesDir, relativePath)

    fun deleteShots(context: Context, messages: List<ChatMessage>) {
        for (m in messages) {
            m.imagePath?.let { fileFor(context, it).delete() }
        }
    }
}
