package dev.jawsh.labelscan.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTableParserTest {
    @Test fun throwawayReportUpcLeadsTheRow() {
        val lines = listOf(
            "UPC Item Code Item Description Key Qty Estimated Cost",
            "4125051227 719155 FDK LEMON BLUEBERRY COOKIE 13.5OZ 53 9 2.60 23.40 THROW TO COMPACTOR",
            "7192321854 729233 FFM CAKE DONUT POWDERED 6CT 53 12 1.61 19.31 THROW TO COMPACTOR",
        )
        val rows = TextTableParser.parse(lines)
        assertEquals(2, rows.size)
        assertEquals("FDK LEMON BLUEBERRY COOKIE 13.5OZ", rows[0].name)
        assertEquals("13.5 OZ", rows[0].size)
        assertEquals("4125051227", rows[0].orderCode)
        assertTrue(rows[0].upcValid)
        assertEquals("FFM CAKE DONUT POWDERED 6CT", rows[1].name)
    }

    @Test fun keepsSpaceSeparatedTrailingCount() {
        val row = TextTableParser.parse(
            listOf("4125000069 608042 COOKIE ULT PEANUT BUTTER 20 CT 53 1 3.00 3.00 THROW TO COMPACTOR"),
        ).single()
        assertEquals("COOKIE ULT PEANUT BUTTER 20 CT", row.name)
    }

    @Test fun orderBookDashedUpcTrailsTheRow() {
        // Reconstructed order-book row: description leads, dashed UPC on the same line.
        val lines = listOf(
            "SPREAD HERBED GARLIC 288579 15 LB 07-19283-02727 1 4 20 ACT",
            "PECAN PIECES MEDIUM 5 LBS 054491 5 LB 09-47761-40480 1 2 20 ACT",
            "ICING CAKE CREAM CHEESE TFA 826821 18 LBS 00-29519-06281 1 4 20 ACT",
        )
        val rows = TextTableParser.parse(lines)
        assertEquals(3, rows.size)

        assertEquals("SPREAD HERBED GARLIC", rows[0].name)
        assertEquals("719283027276", rows[0].upc) // dashed 07-19283-02727 -> UPC-A
        assertEquals("15 LB", rows[0].size)

        assertEquals("PECAN PIECES MEDIUM 5 LBS", rows[1].name)
        assertEquals("094776140480", rows[1].upc)
        assertEquals("5 LBS", rows[1].size)

        assertEquals("ICING CAKE CREAM CHEESE TFA", rows[2].name)
        assertEquals("029519062811", rows[2].upc)
    }

    @Test fun ignoresLinesWithoutAUpc() {
        assertEquals(emptyList<OrderRow>(), TextTableParser.parse(listOf("Page 1 of 2", "STORE 135 DEPT 53")))
    }
}
