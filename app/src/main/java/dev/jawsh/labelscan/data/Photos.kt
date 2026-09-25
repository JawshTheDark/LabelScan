package dev.jawsh.labelscan.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max

object Photos {
    /** OCR wants lots of pixels on the small label print; stored copies don't. */
    const val OCR_MAX_PX = 3000
    private const val STORE_MAX_PX = 1600

    fun dir(context: Context) = File(context.filesDir, "photos").apply { mkdirs() }

    fun scaleDown(src: Bitmap, maxPx: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxPx) return src
        val f = maxPx.toFloat() / longest
        return Bitmap.createScaledBitmap(src, (src.width * f).toInt(), (src.height * f).toInt(), true)
    }

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Saves a downscaled JPEG copy and returns its absolute path. */
    fun store(context: Context, bitmap: Bitmap): String {
        val file = File(dir(context), "scan_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { scaleDown(bitmap, STORE_MAX_PX).compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return file.absolutePath
    }

    /** Writes already-encoded image bytes (e.g. downloaded from the backend) to a local file. */
    fun storeBytes(context: Context, bytes: ByteArray): String {
        val file = File(dir(context), "dl_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { it.write(bytes) }
        return file.absolutePath
    }

    /** Decodes a gallery image upright and no larger than [maxPx]. */
    fun load(context: Context, uri: Uri, maxPx: Int = OCR_MAX_PX): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val bmp = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val degrees = resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        return scaleDown(rotate(bmp, degrees), maxPx)
    }

    /** A small preview for lists; null if the file is gone. */
    fun thumbnail(path: String, maxPx: Int): Bitmap? {
        if (path.isEmpty() || !File(path).exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
