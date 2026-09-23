package dev.jawsh.labelscan.parse

/** UPC / EAN / GTIN helpers. All GTIN lengths share one mod-10 check digit. */
object Gtin {
    private val LENGTHS = setOf(8, 12, 13, 14)

    /**
     * Maps characters OCR commonly confuses with digits; spaces and dashes are
     * dropped, anything else becomes '?' so callers can reject the run.
     */
    fun digitize(s: String): String = buildString {
        for (c in s) when (c) {
            in '0'..'9' -> append(c)
            'O', 'o', 'D', 'Q' -> append('0')
            'I', 'l', 'i', '|', '!' -> append('1')
            'S', 's' -> append('5')
            'B' -> append('8')
            'Z', 'z' -> append('2')
            'G' -> append('6')
            ' ', '-' -> {}
            else -> append('?')
        }
    }

    /** Check digit for [body] (the code without its final digit). */
    fun checkDigit(body: String): Int {
        var sum = 0
        for ((i, c) in body.reversed().withIndex()) {
            sum += (c - '0') * if (i % 2 == 0) 3 else 1
        }
        return (10 - sum % 10) % 10
    }

    fun isValid(code: String): Boolean =
        code.length in LENGTHS && code.all { it in '0'..'9' } &&
            checkDigit(code.dropLast(1)) == code.last() - '0'

    /**
     * Repairs codes a spreadsheet stripped leading zeros from (e.g. an 11-digit
     * UPC-A); returns [code] unchanged when no padding makes it valid.
     */
    fun restoreLeadingZeros(code: String): String {
        if (isValid(code) || code.isEmpty() || !code.all { it in '0'..'9' }) return code
        for (len in listOf(12, 13, 14)) {
            if (code.length < len) {
                val padded = code.padStart(len, '0')
                if (isValid(padded)) return padded
            }
        }
        return code
    }
}
