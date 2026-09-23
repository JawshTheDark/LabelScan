package dev.jawsh.labelscan.data

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.jawsh.labelscan.parse.BarcodeBox
import dev.jawsh.labelscan.parse.LabelData
import dev.jawsh.labelscan.parse.LabelParser
import dev.jawsh.labelscan.parse.OcrLine
import dev.jawsh.labelscan.parse.OrderRow
import dev.jawsh.labelscan.parse.OrderSheetParser
import dev.jawsh.labelscan.parse.ScannedBarcode
import dev.jawsh.labelscan.parse.TextBox
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

    /**
     * Reads every catalog row off a photographed order-book page. Picks the page
     * rotation that exposes the most numeric barcodes, then anchors rows on them.
     */
    suspend fun readOrderSheet(bitmap: Bitmap): List<OrderRow> {
        var chosen: Bitmap = bitmap
        var bestBarcodes = listOf<Barcode>()
        for (rotation in listOf(0, 90, 270, 180)) {
            val rotated = Photos.rotate(bitmap, rotation)
            val found = runCatching {
                barcodes.process(InputImage.fromBitmap(rotated, 0)).await()
            }.getOrDefault(emptyList())
            val numeric = found.count { (it.rawValue ?: "").filter(Char::isDigit).length in 11..13 }
            if (numeric > bestBarcodes.count { (it.rawValue ?: "").filter(Char::isDigit).length in 11..13 }) {
                bestBarcodes = found
                chosen = rotated
            }
            if (rotation == 0 && numeric >= 2) break // upright is the common case
        }
        if (bestBarcodes.isEmpty()) return emptyList()

        val result = text.process(InputImage.fromBitmap(chosen, 0)).await()
        val texts = result.textBlocks.flatMap { b -> b.lines }.mapNotNull { line ->
            val r = line.boundingBox ?: return@mapNotNull null
            TextBox(line.text, r.left, r.top, r.width(), r.height())
        }
        val boxes = bestBarcodes.mapNotNull { b ->
            val r = b.boundingBox ?: return@mapNotNull null
            b.rawValue?.let { BarcodeBox(it, r.left, r.top, r.width(), r.height()) }
        }
        return OrderSheetParser.parse(texts, boxes)
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
