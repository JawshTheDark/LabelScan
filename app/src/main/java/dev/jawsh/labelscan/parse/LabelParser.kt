package dev.jawsh.labelscan.parse

/** A barcode ML Kit decoded from the photo. */
data class ScannedBarcode(val value: String, val isProductCode: Boolean)

/** One recognised line of text; [height] is its glyph height in pixels (0 = unknown). */
data class OcrLine(val text: String, val height: Float = 0f)

/** Where the UPC came from, which decides how much it can be trusted. */
enum class UpcSource {
    NONE,
    /** Decoded from a retail barcode — exact. */
    BARCODE,
    /** Printed digits whose own check digit agrees — OCR misreads would be caught. */
    PRINTED_CHECKED,
    /** Printed without a check digit (how case labels print it); we added one, so misreads can't be caught. */
    PRINTED_COMPLETED,
    /** Printed digits whose check digit disagrees — likely a misread. */
    PRINTED_BAD,
}

/** Fields pulled off one label. Empty string = not found. */
data class LabelData(
    val name: String = "",
    val category: String = "",
    val upc: String = "",
    val upcSource: UpcSource = UpcSource.NONE,
    /** The UPC digits exactly as printed, when they differ from [upc]. */
    val upcPrinted: String = "",
    val itemNo: String = "",
    val size: String = "",
    val dept: String = "",
    val plu: String = "",
    val slot: String = "",
    val door: String = "",
    val caseNo: Int? = null,
    val caseTotal: Int? = null,
    val caseId: String = "",
    val asg: String = "",
    val rawText: String = "",
) {
    val upcValid: Boolean get() = Gtin.isValid(upc)

    /** How much of the label was understood; used to pick the best OCR rotation. */
    fun score(): Int =
        (if (upcValid) 10 else if (upc.isNotEmpty()) 3 else 0) +
            (if (itemNo.isNotEmpty()) 3 else 0) +
            (if (name.isNotEmpty()) 3 else 0) +
            listOf(size, slot, door, asg, dept, plu).count { it.isNotEmpty() } +
            (if (caseNo != null) 1 else 0)

    val isComplete: Boolean get() = upcValid && name.isNotEmpty() && (itemNo.isNotEmpty() || upcSource == UpcSource.BARCODE)
}

/**
 * Turns OCR'd label text into [LabelData]. Tuned for grocery-DC case labels like:
 *
 *   C8-30-S   3 of 7   ACE BISTRO LOAF SOUR
 *   DOUGH 21 OZ        M012/Q001 09/22
 *   ITM870628 21 OZ    ASG#1029801153
 *   UPC#89049700030    BKY
 *   ...                A-MF-36-06-004
 *
 * and, loosely, store-printed retail labels (name in big type, PLU, barcode).
 * Every field is optional; the review screen lets the user fix what's missing.
 */
object LabelParser {
    private const val DIGITISH = "[0-9OoIlSBZ|]"

    // No spaces inside the capture: "UPC#89049700030 BKY" must not read the B as an 8.
    private val UPC = Regex("""U\s?P\s?C\s?[#:*]?\s?($DIGITISH{6,14})(?![0-9A-Za-z])""")
    private val ITEM = Regex("""\bI\s?T\s?M\s?#?\s?($DIGITISH{4,10})""")
    private val ASG = Regex("""\bA\s?S\s?G\s?#?\s?($DIGITISH{6,14})""")
    private val PLU = Regex("""\bPLU\s?#?\s?:?\s?(\d{3,5})\b""", RegexOption.IGNORE_CASE)
    private val SIZE = Regex(
        """\b(\d+(?:\.\d+)?)\s?(FL\s?[O0]Z|[O0]Z|LBS?|CT|PK|KG|ML|GAL|EA|G|L)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val PACK = Regex("""\b([1-9]\d*)\s?/\s?(CS|CASE)\b""")
    private val CASE = Regex("""\b(\d{1,3})\s+[oO0][fF]\s+(\d{1,3})\b""")
    private val SLOT = Regex("""\b([A-Z])\s?-\s?([A-Z0-9]{1,3})\s?-\s?(\d{1,3})\s?-\s?(\d{1,3})\s?-\s?(\d{1,4})\b""")
    private val DOOR = Regex("""\b([A-Z]\d{1,2}-\d{1,3}-[A-Z])\b""")
    private val LONE_NUMBER = Regex("""(?<![\d#])(\d{11,13})(?!\d)""")

    private val KNOWN_DEPTS = listOf(
        "BKY", "FROZ", "FRZ", "DELI", "DLI", "DAIRY", "DRY", "GROC", "GRO", "PROD", "MEAT", "SEA", "HBC", "FLRL",
    )
    /** Short all-caps words that show up on labels but aren't departments. */
    private val NOT_DEPT = setOf("UPC", "ITM", "ASG", "LBS", "GAL", "NEW", "CASE", "EACH", "REG")
    /** Lines that are never the product name. */
    private val NOT_NAME = Regex(
        "^(NO PRIMARY|REG PRICE|SELL BY|BEST BY|USE BY|PACKED ON|INGREDIENTS|CONTAINS|KEEP |DIST\\.? BY|" +
            // Nutrition facts panel
            "NUTRITION|SERVING|AMOUNT PER|CALORIES|TOTAL |SATURATED|TRANS FAT|CHOLESTEROL|SODIUM|DIETARY|" +
            "SUGAR|PROTEIN|VITAMIN|CALCIUM|IRON|POTASSIUM|NOT A SIGNIFICANT|PERCENT DAILY|\\* )",
        RegexOption.IGNORE_CASE,
    )

    fun parse(lines: List<String>, barcodes: List<ScannedBarcode> = emptyList()): LabelData =
        parseLines(lines.map { OcrLine(it) }, barcodes)

    fun parseLines(ocr: List<OcrLine>, barcodes: List<ScannedBarcode> = emptyList()): LabelData {
        val lines = ocr.map { it.copy(text = it.text.replace(Regex("\\s+"), " ").trim()) }.filter { it.text.isNotEmpty() }
        val clean = lines.map { it.text }
        val text = clean.joinToString("\n")

        val asg = ASG.find(text)?.let { Gtin.digitize(it.groupValues[1]) }?.takeIf { '?' !in it } ?: ""
        val caseId = barcodes.firstOrNull { !it.isProductCode }?.value?.trim() ?: ""
        val case = CASE.find(text)?.let { m ->
            val n = m.groupValues[1].toInt()
            val t = m.groupValues[2].toInt()
            if (n in 1..t) n to t else null
        }
        val upc = findUpc(clean, barcodes, exclude = setOf(asg, caseId))
        val name = findName(lines)

        return LabelData(
            name = name,
            category = findCategory(clean, name),
            upc = upc.code,
            upcSource = upc.source,
            upcPrinted = upc.printed.takeIf { it != upc.code } ?: "",
            itemNo = ITEM.find(text)?.let { Gtin.digitize(it.groupValues[1]) }?.takeIf { '?' !in it } ?: "",
            size = SIZE.find(text)?.let { formatSize(it) }
                ?: PACK.find(text)?.let { "${it.groupValues[1]}/CS" } ?: "",
            dept = findDept(clean),
            plu = PLU.find(text)?.groupValues?.get(1) ?: "",
            slot = SLOT.find(text)?.groupValues?.drop(1)?.joinToString("-") ?: "",
            door = DOOR.find(text)?.groupValues?.get(1) ?: "",
            caseNo = case?.first,
            caseTotal = case?.second,
            caseId = caseId,
            asg = asg,
            rawText = text,
        )
    }

    private fun formatSize(m: MatchResult): String {
        val unit = m.groupValues[2].uppercase().replace('0', 'O').replace(" ", "")
        return "${m.groupValues[1]} ${if (unit == "FLOZ") "FL OZ" else unit}"
    }

    private data class Upc(val code: String, val source: UpcSource, val printed: String)

    /**
     * Case labels print the UPC-A *without* its check digit (11 digits, e.g.
     * `UPC#89049700030` for retail barcode 890497000306), possibly with leading
     * zeros dropped. A printed 12-14 digit code that checksums is taken as-is.
     */
    private fun fromPrinted(digits: String): Upc? {
        if (digits.length in 12..14 && Gtin.isValid(digits)) return Upc(digits, UpcSource.PRINTED_CHECKED, digits)
        return when (digits.length) {
            in 6..11 -> digits.padStart(11, '0').let { Upc(it + Gtin.checkDigit(it), UpcSource.PRINTED_COMPLETED, digits) }
            12 -> Upc(digits + Gtin.checkDigit(digits), UpcSource.PRINTED_COMPLETED, digits)
            13, 14 -> Upc(digits, UpcSource.PRINTED_BAD, digits)
            else -> null
        }
    }

    private fun findUpc(lines: List<String>, barcodes: List<ScannedBarcode>, exclude: Set<String>): Upc {
        // 1. A retail barcode on the package is exact — trust it over OCR.
        barcodes.firstOrNull { it.isProductCode && Gtin.isValid(it.value) }
            ?.let { return Upc(it.value, UpcSource.BARCODE, it.value) }

        // 2. The "UPC#" field on the label.
        for (line in lines) {
            val m = UPC.find(line) ?: continue
            val digits = Gtin.digitize(m.groupValues[1]).substringBefore('?').take(14)
            fromPrinted(digits)?.let { return it }
        }

        // 3. A bare 11-13 digit number on its own, e.g. labels that omit "UPC#".
        return lines.asSequence()
            .flatMap { LONE_NUMBER.findAll(it).map { m -> m.groupValues[1] } }
            .filter { it !in exclude }
            .firstNotNullOfOrNull { fromPrinted(it) }
            ?: Upc("", UpcSource.NONE, "")
    }

    private fun findDept(lines: List<String>): String {
        val tokens = lines.flatMap { it.split(' ') }
        KNOWN_DEPTS.firstOrNull { it in tokens }?.let { return it }
        return lines.firstOrNull { Regex("^[A-Z]{3,4}$").matches(it) && it !in NOT_DEPT } ?: ""
    }

    /**
     * Strips a line down to its wordy part: drops "N of M", door/slot codes,
     * tokens with '/', '#' or '$', and leading numbers ("223401 FFM BROOKIE..." → "FFM BROOKIE...").
     * Null when what's left doesn't look like a product description.
     */
    private fun wordy(raw: String): String? {
        if (NOT_NAME.containsMatchIn(raw)) return null
        if (Regex("""^(UPC|ITM|ASG|PLU)""").containsMatchIn(raw)) return null
        val stripped = raw.replace(CASE, " ").replace(DOOR, " ").replace(SLOT, " ")
        val tokens = stripped.split(' ').filter { t -> t.isNotEmpty() && t.none { it in "/#$%" } }
            .dropWhile { t -> t.none { it.isLetter() } }
            .dropLastWhile { t -> t.none { it.isLetterOrDigit() } }
        val s = tokens.joinToString(" ")
        val compact = s.replace(" ", "")
        val letters = compact.count { it.isLetter() }
        return when {
            tokens.size < 2 || s.length < 5 -> null
            letters < compact.length * 0.7 -> null
            else -> s
        }
    }

    /**
     * The product description: most letters wins, digits count against, and
     * on retail labels bigger type counts for a lot (the name is the headline;
     * ingredient lists are long but tiny).
     */
    private fun findName(lines: List<OcrLine>): String {
        val heights = lines.map { it.height }.filter { it > 0 }.sorted()
        val median = heights.getOrNull(heights.size / 2) ?: 0f
        fun rel(line: OcrLine) = if (median > 0 && line.height > 0) line.height / median else 1f

        val best = lines.indices.mapNotNull { i ->
            val s = wordy(lines[i].text) ?: return@mapNotNull null
            val base = s.count { it.isLetter() } - 2 * s.count { it.isDigit() }
            val scale = rel(lines[i]).coerceIn(0.3f, 3f)
            Triple(i, s, base * scale * scale)
        }.maxByOrNull { it.third }?.takeIf { it.third > 0 } ?: return ""

        // A headline in big type often wraps ("FFM TURNOVERS" / "APPLE 4CT"): take the
        // following lines of the same size too. Case labels print everything one size, so skip there.
        var name = best.second
        val h = lines[best.first].height
        if (rel(lines[best.first]) >= 1.5f) {
            for (next in lines.drop(best.first + 1)) {
                if (next.height !in h * 0.8f..h * 1.25f) break
                if (NOT_NAME.containsMatchIn(next.text) || next.text.any { it in "/#$%" }) break
                name += " " + next.text
            }
        }
        return name
    }

    /** A secondary "DOUGH 21 OZ"-style line: words followed by a size. */
    private fun findCategory(lines: List<String>, name: String): String =
        lines.mapNotNull { wordy(it) }
            .firstOrNull { it != name && SIZE.containsMatchIn(it) }
            ?.let { SIZE.replace(it, "").trim() }
            ?.takeIf { it.isNotEmpty() && it.none(Char::isDigit) } ?: ""
}
