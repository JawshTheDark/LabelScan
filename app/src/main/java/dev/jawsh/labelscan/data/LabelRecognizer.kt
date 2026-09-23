package dev.jawsh.labelscan.data

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.jawsh.labelscan.parse.LabelData
import dev.jawsh.labelscan.parse.LabelParser
import dev.jawsh.labelscan.parse.OcrLine
import dev.jawsh.labelscan.parse.ScannedBarcode
import kotlinx.coroutines.tasks.await
import kotlin.math.hypot

/** On-device OCR + barcode reading for one photo of a case label. */
class LabelRecognizer {
    private val text = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val barcodes = BarcodeScanning.getClient()

    private val productFormats = setOf(
        Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E, Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
    )

    /**
     * Labels get slapped on boxes at any angle, and OCR does poorly on sideways
     * text, so try each quarter turn and keep whichever reading parses best.
     */
    suspend fun read(bitmap: Bitmap): LabelData {
        val codes = runCatching {
            barcodes.process(InputImage.fromBitmap(bitmap, 0)).await().mapNotNull { b ->
                b.rawValue?.let { ScannedBarcode(it, b.format in productFormats) }
            }
        }.getOrDefault(emptyList())

        var best = LabelData()
        for (rotation in listOf(0, 90, 270, 180)) {
            val result = text.process(InputImage.fromBitmap(bitmap, rotation)).await()
            val lines = result.textBlocks.flatMap { block -> block.lines.map { OcrLine(it.text, lineHeight(it)) } }
            val parsed = LabelParser.parseLines(lines, codes)
            if (parsed.score() > best.score()) best = parsed
            if (best.isComplete) break
        }
        return best
    }

    /** Glyph height from the rotated box corners (top-left to bottom-left), so slanted labels measure right. */
    private fun lineHeight(line: Text.Line): Float {
        val p = line.cornerPoints ?: return line.boundingBox?.height()?.toFloat() ?: 0f
        if (p.size < 4) return 0f
        return hypot((p[3].x - p[0].x).toFloat(), (p[3].y - p[0].y).toFloat())
    }

    fun close() {
        text.close()
        barcodes.close()
    }
}
