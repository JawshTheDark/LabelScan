package dev.jawsh.labelscan.parse

/**
 * EAN-13 / UPC-A bar patterns, so a stored UPC can be shown on screen and
 * scanned by a handheld inventory gun.
 */
object BarcodeEncoder {
    const val MODULES = 95

    private val L = arrayOf(
        "0001101", "0011001", "0010011", "0111101", "0100011",
        "0110001", "0101111", "0111011", "0110111", "0001011",
    )
    private val PARITY = arrayOf(
        "LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG",
        "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL",
    )
    private val GUARDS = (0..2) + (45..49) + (92..94)

    private fun r(d: Int) = L[d].map { if (it == '0') '1' else '0' }.joinToString("")
    private fun g(d: Int) = r(d).reversed()

    /** True for modules that belong to the (taller) guard bars. */
    fun isGuard(module: Int) = module in GUARDS

    /** 95 modules (true = black bar) for a valid 12-digit UPC-A or 13-digit EAN-13, else null. */
    fun modules(code: String): BooleanArray? {
        val ean = when (code.length) {
            12 -> "0$code"
            13 -> code
            else -> return null
        }
        if (!Gtin.isValid(ean)) return null
        val d = ean.map { it - '0' }
        val parity = PARITY[d[0]]
        val bits = buildString {
            append("101")
            for (i in 1..6) append(if (parity[i - 1] == 'L') L[d[i]] else g(d[i]))
            append("01010")
            for (i in 7..12) append(r(d[i]))
            append("101")
        }
        return BooleanArray(MODULES) { bits[it] == '1' }
    }
}
