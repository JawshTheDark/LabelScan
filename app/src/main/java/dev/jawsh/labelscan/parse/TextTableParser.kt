package dev.jawsh.labelscan.parse

/**
 * Parses plain-text report rows where the UPC is printed (no barcode), such as
 * the Throwaway Batch Report:
 *
 *   4125051227  719155  FDK LEMON BLUEBERRY COOKIE 13.5OZ  53  9  2.60 23.40 THROW TO COMPACTOR
 *   <UPC>       <item>  <description……………………>            <key><qty><costs…> <reason>
 *
 * Each row starts with a 10–13 digit UPC (leading zero + check digit omitted,
 * like the case labels) and an item code, then the description, then numeric
 * columns. The description ends where the numeric columns begin.
 */
object TextTableParser {
    private val ROW = Regex("""^(\d{10,13})\s+(\d{3,7})\s+(.+)$""")
    private val INT = Regex("""\d{1,4}""")
    private val NUM = Regex("""\d+(\.\d+)?""")
    private val SIZE = Regex("""\b(\d+(?:\.\d+)?)\s?(FL\s?OZ|OZ|LBS?|CT|PK|KG|ML|GAL|EA|G|L)\b""", RegexOption.IGNORE_CASE)

    fun parse(lines: List<String>): List<OrderRow> = lines.mapNotNull { parseLine(it) }

    private fun parseLine(raw: String): OrderRow? {
        val m = ROW.find(raw.trim()) ?: return null
        val upcRaw = m.groupValues[1]
        val itemCode = m.groupValues[2]
        val tokens = m.groupValues[3].split(Regex("\\s+")).filter { it.isNotEmpty() }

        // Description runs until the numeric columns (Key, Qty, costs): the first
        // small integer that is itself followed by another number. A trailing
        // "20 CT" is kept because "CT" isn't numeric.
        var end = tokens.size
        for (i in tokens.indices) {
            if (INT.matches(tokens[i]) && tokens.getOrNull(i + 1)?.let { NUM.matches(it) } == true) {
                end = i
                break
            }
        }
        val nameTokens = tokens.subList(0, end)
        val name = nameTokens.joinToString(" ").trim()
        if (name.count(Char::isLetter) < 3) return null

        return OrderRow(
            upc = OrderSheetParser.upcFromOrderCode(upcRaw),
            orderCode = upcRaw,
            name = name,
            size = SIZE.find(name)?.let { "${it.groupValues[1]} ${it.groupValues[2].uppercase().replace(" ", "")}" } ?: "",
            section = "",
            codes = listOf(itemCode),
            rawText = raw.trim(),
        )
    }
}
