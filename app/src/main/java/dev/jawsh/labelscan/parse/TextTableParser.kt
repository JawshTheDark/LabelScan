package dev.jawsh.labelscan.parse

/**
 * Parses report rows where the UPC is printed as text, pairing it with the
 * description on the same row. Handles both report shapes:
 *
 *  Throwaway Batch Report — UPC leads the row:
 *   4125051227 719155 FDK LEMON BLUEBERRY COOKIE 13.5OZ 53 9 2.60 23.40 THROW TO COMPACTOR
 *
 *  Distribution Order Book — UPC (dashed) trails the row, description leads:
 *   PECAN PIECES MEDIUM 5 LBS 054491 5 LB 09-47761-40480 1 2 20 ACT
 *
 * The UPC is found wherever it sits (leading digits, or a dashed nn-nnnnn-nnnnn),
 * and the description is the longest run of word/size tokens — so it doesn't
 * matter which side of the row the codes are on.
 */
object TextTableParser {
    private val UPC_DASH = Regex("""\d{2}-\d{5}-\d{5}""")
    private val UPC_PLAIN = Regex("""(?<!\d)\d{10,13}(?!\d)""")
    // ILC shelf location on a Changed Products report, e.g. "A-36-2-16"; New ILC is the last one.
    private val ILC = Regex("""\b[A-Z]-\d{1,2}-\d{1,2}-\d{1,2}\b""")
    private val SIZE = Regex("""^\d+(\.\d+)?(FL)?(OZ|LBS?|CT|PK|KG|ML|GAL|EA|G|L)$""", RegexOption.IGNORE_CASE)
    private val PURE_INT = Regex("""\d{1,3}""")
    private val CODE = Regex("""\d{4,}""") // meijer/product/vendor/item codes and column numbers
    private val MONEY = Regex("""\d+\.\d{2}""")
    // Column/status tokens that break a description run. Header-only words like MEIJER,
    // PRODUCT, DESCRIPTION and VENDOR are deliberately NOT here — they occur in real product
    // names ("FRESH FROM MEIJER"), and header lines are already dropped for having no UPC.
    private val STATUS = Regex(
        "^(ACT|ANR|THROW|COMPACTOR|SALE|ORD|SHELF|FREEZER|REFRIGER|BKY|FROZ|DEPT|STORE|PAGE|UPC|PACK|" +
            "STATUS|HI|TI|KEY|QTY|EST|EXTENDED|ESTIMATED|REASON)$",
        RegexOption.IGNORE_CASE,
    )

    fun parse(lines: List<String>): List<OrderRow> = lines.mapNotNull { parseLine(it) }

    private fun parseLine(raw: String): OrderRow? {
        val line = raw.trim()
        val tokens = line.split(Regex("\\s+")).filter { it.isNotEmpty() }

        // On these reports the UPC is the first cell, and some are only 9 digits
        // (e.g. Bill Knapps 822910100) — shorter than the mid-line UPC_PLAIN allows.
        // A pure-digit leading token of catalog length is taken as the UPC; otherwise
        // fall back to a dashed or 10-13 digit run anywhere on the row.
        val leading = tokens.firstOrNull()?.takeIf { it.all(Char::isDigit) && it.length in 9..13 }
        val upcRaw = (leading ?: UPC_DASH.find(line)?.value ?: UPC_PLAIN.find(line)?.value)
            ?.filter { it.isDigit() } ?: return null
        if (upcRaw.length < 9) return null

        // Longest contiguous run of description words (letters, or a size like
        // "20 CT" that belongs to the name); codes/UPC/status/column numbers break it.
        var best = IntRange.EMPTY
        var run = -1
        for (i in tokens.indices) {
            if (isWord(tokens, i)) {
                if (run < 0) run = i
                if (best.isEmpty() || i - run >= best.last - best.first) best = run..i
            } else {
                run = -1
            }
        }
        if (best.isEmpty()) return null
        val name = tokens.slice(best).joinToString(" ").trim()
        if (name.count(Char::isLetter) < 3) return null

        val size = tokens.firstOrNull { SIZE.matches(it) }
            ?.let { Regex("""^(\d+(?:\.\d+)?)(.*)$""").find(it)!!.let { m -> "${m.groupValues[1]} ${m.groupValues[2].uppercase()}" } }
            ?: sizeFromPair(tokens)

        return OrderRow(
            upc = OrderSheetParser.upcFromOrderCode(upcRaw),
            orderCode = upcRaw,
            name = name,
            size = size,
            section = "",
            // New ILC (the last one) is the current location. On a deleted row the New
            // ILC column is "-", so a trailing dash means there's no new location — don't
            // fall back to the Previous ILC and claim the item is still shelved there.
            location = if (tokens.lastOrNull() in setOf("-", "–", "—")) "" else ILC.findAll(line).lastOrNull()?.value ?: "",
            codes = tokens.filter { CODE.matches(it) && it != upcRaw },
            rawText = line,
        )
    }

    private val UNIT = Regex("""(FL)?(OZ|LBS?|CT|PK|KG|ML|GAL|EA|G|L)""", RegexOption.IGNORE_CASE)

    /** A description token at index [i]: a name word, a size, or a "5 LB"-style size number/unit. */
    private fun isWord(tokens: List<String>, i: Int): Boolean {
        val t = tokens[i]
        if (SIZE.matches(t) || UNIT.matches(t)) return true // "13.5OZ", "6CT", or a bare "LB"
        if (STATUS.matches(t) || MONEY.matches(t) || CODE.matches(t) || UPC_DASH.matches(t)) return false
        // A number counts only when it's the value of a following size unit ("20" in "20 CT", "21.1" in "21.1 OZ").
        if (t.matches(Regex("""\d+(\.\d+)?"""))) return tokens.getOrNull(i + 1)?.let { UNIT.matches(it) } == true
        val letters = t.count(Char::isLetter)
        return letters >= 2 && letters >= t.count { !it.isWhitespace() } * 0.5
    }

    /** Handles a size written as two tokens, e.g. "5 LB" or "21 OZ". */
    private fun sizeFromPair(tokens: List<String>): String {
        for (i in 0 until tokens.size - 1) {
            val a = tokens[i]; val b = tokens[i + 1]
            if (a.matches(Regex("""\d+(\.\d+)?""")) && b.matches(Regex("""(FL)?(OZ|LBS?|CT|PK|KG|ML|GAL|EA|G|L)""", RegexOption.IGNORE_CASE))) {
                return "$a ${b.uppercase()}"
            }
        }
        return ""
    }
}
