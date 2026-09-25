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
import dev.jawsh.labelscan.parse.TextTableParser
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
     * Reads every catalog row off a photographed order-book page. Orientation is
     * chosen by how much *text* comes back upright (barcodes decode at any angle,
     * so they can't tell us which way is up), then the page is processed in
     * overlapping horizontal tiles so each small barcode and description line is
     * read at higher effective resolution — a full page at once loses most of them.
     */
    suspend fun readOrderSheet(bitmap: Bitmap): List<OrderRow> {
        val upright = uprightForText(bitmap)

        val texts = ArrayList<TextBox>()
        val boxes = ArrayList<BarcodeBox>()
        val h = upright.height
        val w = upright.width

        // Text: full-width horizontal strips keep each line intact.
        val strips = 4
        val stripH = h / strips
        var y = 0
        while (y < h) {
            val th = minOf(stripH + stripH / 4, h - y)
            if (th < 40) break
            val tile = Bitmap.createBitmap(upright, 0, y, w, th)
            runCatching { text.process(InputImage.fromBitmap(tile, 0)).await() }.getOrNull()?.textBlocks
                ?.flatMap { it.lines }?.forEach { line ->
                    val r = line.boundingBox ?: return@forEach
                    texts += TextBox(line.text, r.left, r.top + y, r.width(), r.height())
                }
            tile.recycle()
            y += stripH
        }

        // Barcodes: a fine grid so each small Code-128 is large relative to its tile —
        // the single biggest lever on how many rows come back. Whole-page pass first.
        runCatching { barcodes.process(InputImage.fromBitmap(upright, 0)).await() }.getOrNull()
            ?.forEach { b -> b.boundingBox?.let { r -> b.rawValue?.let { boxes += BarcodeBox(it, r.left, r.top, r.width(), r.height()) } } }
        val cols = 2
        val rows = (h / 380).coerceIn(4, 12) // ~2–3 barcode-heights per tile
        val cw = w / cols
        val ch = h / rows
        val ox = cw / 5
        val oy = ch / 3
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val x0 = (c * cw - ox).coerceAtLeast(0)
                val y0 = (r * ch - oy).coerceAtLeast(0)
                val tw = minOf(cw + 2 * ox, w - x0)
                val thh = minOf(ch + 2 * oy, h - y0)
                if (tw < 40 || thh < 40) continue
                val tile = Bitmap.createBitmap(upright, x0, y0, tw, thh)
                runCatching { barcodes.process(InputImage.fromBitmap(tile, 0)).await() }.getOrNull()
                    ?.forEach { b ->
                        val bb = b.boundingBox ?: return@forEach
                        b.rawValue?.let { boxes += BarcodeBox(it, bb.left + x0, bb.top + y0, bb.width(), bb.height()) }
                    }
                tile.recycle()
            }
        }

        // Overlap re-reads the seam; keep one barcode per value and drop duplicate text lines.
        val seenCodes = HashSet<String>()
        val uniqueBoxes = boxes.filter { seenCodes.add(it.value.filter(Char::isDigit)) }
        val seenText = HashSet<String>()
        val uniqueTexts = texts.filter { seenText.add("${it.text.trim()}@${it.y / 25}") }

        // Two report shapes: barcode-anchored order books, and plain-text reports
        // (Throwaway Batch Report) where the UPC is printed. Run both and merge.
        val fromBarcodes = OrderSheetParser.parse(uniqueTexts, uniqueBoxes)
        val fromText = TextTableParser.parse(reconstructRows(uniqueTexts))

        val byUpc = LinkedHashMap<String, OrderRow>()
        for (r in fromBarcodes + fromText) {
            if (r.upc.isBlank()) continue
            val existing = byUpc[r.upc]
            // Prefer the reading that actually recovered a name.
            byUpc[r.upc] = when {
                existing == null -> r
                existing.name.isBlank() && r.name.isNotBlank() -> r
                else -> existing
            }
        }
        return byUpc.values.toList()
    }

    /**
     * Rebuilds full-width row strings from column text boxes by grouping on their
     * baseline Y. A row's cells share one baseline, so each new box is compared to
     * the *anchor* (first box) of the current row, not a running average — averaging
     * drifts and lets a whole stack of separate lines (a page's "Business Area /
     * Department / POG Category" header) collapse into one bogus row. The tolerance
     * sits well under a table's row pitch so stacked lines stay separate.
     */
    private fun reconstructRows(texts: List<TextBox>): List<String> {
        if (texts.isEmpty()) return emptyList()
        val medianH = texts.map { it.h }.sorted()[texts.size / 2].coerceAtLeast(1)
        val tol = medianH * 0.5f
        val rows = mutableListOf<MutableList<TextBox>>()
        var anchorCy = Float.NEGATIVE_INFINITY
        for (t in texts.sortedBy { it.cy }) {
            if (rows.isEmpty() || t.cy - anchorCy > tol) {
                rows += mutableListOf(t)
                anchorCy = t.cy
            } else {
                rows.last() += t
            }
        }
        // distinct() drops a cell re-read in an overlapping strip seam.
        return rows.map { r -> r.sortedBy { it.x }.map { it.text.trim() }.distinct().joinToString(" ") }
    }

    /** Rotation (0/90/180/270) of [bitmap] that yields the most recognised text. */
    private suspend fun uprightForText(bitmap: Bitmap): Bitmap {
        val small = Photos.scaleDown(bitmap, 1400)
        var best = bitmap
        var bestChars = -1
        for (rotation in listOf(0, 90, 270, 180)) {
            val probe = Photos.rotate(small, rotation)
            val chars = runCatching { text.process(InputImage.fromBitmap(probe, 0)).await() }
                .getOrNull()?.textBlocks?.sumOf { b -> b.text.count { it.isLetterOrDigit() } } ?: 0
            if (chars > bestChars) {
                bestChars = chars
                best = if (rotation == 0) bitmap else Photos.rotate(bitmap, rotation)
            }
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
