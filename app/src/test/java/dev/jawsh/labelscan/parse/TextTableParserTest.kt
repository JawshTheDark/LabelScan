package dev.jawsh.labelscan.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTableParserTest {
    @Test fun parsesThrowawayReportRows() {
        val lines = listOf(
            "Throwaway Batch Report  St# 135",
            "UPC Item Code Item Description Key Qty Estimated Cost",
            "4125051227 719155 FDK LEMON BLUEBERRY COOKIE 13.5OZ 53 9 2.60 23.40 THROW TO COMPACTOR",
            "7192321854 729233 FFM CAKE DONUT POWDERED 6CT 53 12 1.61 19.31 THROW TO COMPACTOR",
            "8904970024 870629 ACE BISTRO LOAF MULTIGRAIN 21 OZ 53 2 2.70 5.40 THROW TO COMPACTOR",
            "6084042 608042 COOKIE ULT PEANUT BUTTER 20 CT 53 1 3.00 3.00 THROW TO COMPACTOR",
        )
        val rows = TextTableParser.parse(lines)
        assertEquals(3, rows.size) // header lines and the malformed 7-digit UPC row are skipped

        assertEquals("FDK LEMON BLUEBERRY COOKIE 13.5OZ", rows[0].name)
        assertEquals("13.5 OZ", rows[0].size)
        assertTrue(rows[0].upcValid)
        assertEquals("4125051227", rows[0].orderCode)

        assertEquals("FFM CAKE DONUT POWDERED 6CT", rows[1].name)
        assertEquals("ACE BISTRO LOAF MULTIGRAIN 21 OZ", rows[2].name)
        assertEquals("21 OZ", rows[2].size)
    }

    @Test fun keepsTrailingCountButDropsNumericColumns() {
        // "20 CT" stays in the name; "53 1 3.00 3.00" are columns.
        val row = TextTableParser.parse(
            listOf("4125000069 608042 COOKIE ULT PEANUT BUTTER 20 CT 53 1 3.00 3.00 THROW TO COMPACTOR"),
        ).single()
        assertEquals("COOKIE ULT PEANUT BUTTER 20 CT", row.name)
    }

    @Test fun ignoresLinesWithoutAUpc() {
        assertEquals(emptyList<OrderRow>(), TextTableParser.parse(listOf("Page 1 of 2", "just some text")))
    }
}
