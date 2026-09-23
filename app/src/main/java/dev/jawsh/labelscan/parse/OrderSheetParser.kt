package dev.jawsh.labelscan.parse

/** A recognised text line with its pixel bounding box (from ML Kit). */
data class TextBox(val text: String, val x: Int, val y: Int, val w: Int, val h: Int) {
    val cx get() = x + w / 2f
    val cy get() = y + h / 2f
    val right get() = x + w
}

/** A decoded barcode with its bounding box. [value] is the raw symbol payload. */
data class BarcodeBox(val value: String, val x: Int, val y: Int, val w: Int, val h: Int) {
    val cx get() = x + w / 2f
    val cy get() = y + h / 2f
}

/** One catalog line lifted off an order sheet. */
data class OrderRow(
    val upc: String,
    val orderCode: String,
    val name: String,
    val size: String,
    val section: String,
    /** Meijer code, product id, vendor style — kept together, not individually labelled. */
    val codes: List<String>,
    val rawText: String,
) {
    val upcValid get() = Gtin.isValid(upc)
}

/**
 * Parses a photographed distribution order-book page into one [OrderRow] per
 * item. Each printed row carries a Code-128 barcode of its order number, so a
 * barcode anchors its row: text is grouped into the horizontal band around each
 * barcode's baseline. The barcode gives the order number exactly; from it the
 * real UPC-A is reconstructed (the sheet, like the case labels, omits the check
 * digit). The description and other columns come from OCR and are meant to be
 * reviewed before saving.
 */
object OrderSheetParser {
    private val ORDER_CODE = Regex("""^\d{11,13}$""")
    private val SIZE = Regex("""^\d+(\.\d+)?\s?(OZ|LBS?|CT|PK|KG|ML|GAL|EA|G|L)$""", RegexOption.IGNORE_CASE)
    private val NUMERIC = Regex("""^\d{5,10}$""")
    private val MONEY = Regex("""^\d+\.\d{2}$""")
    private val SECTION = Regex("""^\d{3}\s+[A-Z].*""")
    private val DROP = Regex(
        "^(PAGE|STORE|DEPT|RETAIL|DESCRIPTION|VENDOR|MEIJER|PRODUCT|SIZE|UPC|PACK|STATUS|" +
            "FROM|THRU|RUN|SHELF|ORD|ACT|SALE)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** UPC-A from an order code: reduce to the 11-digit body, then append the check digit. */
    fun upcFromOrderCode(code: String): String {
        val d = code.filter { it.isDigit() }
        if (d.length == 12 && Gtin.isValid(d)) return d
        val body = (if (d.length > 11) d.trimStart('0').takeLast(11) else d).padStart(11, '0')
        return body + Gtin.checkDigit(body)
    }

    fun parse(texts: List<TextBox>, barcodes: List<BarcodeBox>): List<OrderRow> {
        val anchors = barcodes
            .filter { ORDER_CODE.matches(it.value.filter { c -> c.isDigit() }) }
            .sortedBy { it.cy }
        if (anchors.isEmpty()) return emptyList()

        // Typical row height = median vertical spacing between consecutive barcodes.
        val gaps = anchors.zipWithNext { a, b -> b.cy - a.cy }.filter { it > 0 }.sorted()
        val rowH = gaps.getOrNull(gaps.size / 2) ?: anchors.first().h * 2.5f

        val sections = texts.filter { SECTION.matches(it.text.trim()) }.sortedBy { it.cy }

        val used = HashSet<TextBox>()
        return anchors.map { bc ->
            // Text in this barcode's horizontal band and to its left (pack/status sit to the right).
            val band = texts.filter {
                it !in used && it.cx < bc.cx + bc.w * 0.2f && kotlin.math.abs(it.cy - bc.cy) <= rowH * 0.55f
            }
            used += band
            buildRow(bc, band, sections)
        }
    }

    private fun buildRow(bc: BarcodeBox, band: List<TextBox>, sections: List<TextBox>): OrderRow {
        val section = sections.lastOrNull { it.cy <= bc.cy + 5 }?.text?.trim() ?: ""
        val cells = band.map { it.text.trim() }
            .filter { it.isNotEmpty() && !DROP.containsMatchIn(it) && !MONEY.matches(it) && !SECTION.matches(it) }

        val leftEdge = band.minOfOrNull { it.x } ?: 0
        val span = ((band.maxOfOrNull { it.right } ?: bc.x) - leftEdge).coerceAtLeast(1)

        // Description = letter-bearing lines in the left ~55%, stacked top-to-bottom.
        val name = band
            .filter { it.text.trim().count(Char::isLetter) >= 3 && !SIZE.matches(it.text.trim()) && !SECTION.matches(it.text.trim()) }
            .filter { it.cx - leftEdge < span * 0.55f }
            .sortedBy { it.cy }
            .joinToString(" ") { it.text.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()

        return OrderRow(
            upc = upcFromOrderCode(bc.value),
            orderCode = bc.value.filter { it.isDigit() },
            name = name,
            size = cells.firstOrNull { SIZE.matches(it) } ?: "",
            section = section,
            codes = cells.filter { NUMERIC.matches(it) },
            rawText = band.sortedWith(compareBy({ it.cy }, { it.x })).joinToString(" | ") { it.text.trim() },
        )
    }
}
