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

    @Test fun changedProductsReportWithIlcLocation() {
        val lines = listOf(
            "UPC Product Name Change Staus Previous ILC New ILC",
            "65708202701 IZZIO ARTISAN BRD T&B BAGUETTE 12 OZ A - A-31-1-8",
            "4125050964 FFM BREAD SANDWICH MULTIGRAIN 21.1 OZ M A-36-2-10 A-36-5-11",
            "89049700030 ACE BISTRO LOAF SOUR DOUGH 21 OZ M A-36-6-6 A-36-6-4",
        )
        val rows = TextTableParser.parse(lines)
        assertEquals(3, rows.size)
        assertEquals("IZZIO ARTISAN BRD T&B BAGUETTE 12 OZ", rows[0].name)
        assertEquals("A-31-1-8", rows[0].location)
        assertEquals("FFM BREAD SANDWICH MULTIGRAIN 21.1 OZ", rows[1].name)
        assertEquals("A-36-5-11", rows[1].location) // New ILC (last), not previous
        assertEquals("890497000306", rows[2].upc)
        assertEquals("A-36-6-4", rows[2].location)
    }

    @Test fun changedProductsShortAndDeletedRows() {
        val lines = listOf(
            "UPC Product Name Change Staus Previous ILC New ILC",
            // Added row: Previous ILC is "-", New ILC is the location.
            "4125051285 FFM 5PK SLICED SOURDOUGH BAGELS A - A-30-7-4",
            // 9-digit UPC (shorter than a mid-line UPC run would allow) taken from the leading cell.
            "822910100 BILL KNAPPS DUNKER NUTTY M A-30-7-10 A-30-7-9",
            "822910903 BILL KNAPPS DUNKER CHOCOLATE M A-30-7-9 A-30-7-8",
            // Deleted row: New ILC is "-", so no current location (not the previous one).
            "4125051138 FFM BREAD CINNAMON 16.9 OZ D A-30-7-13 -",
            "70882010435 FFM 5PK UNSLICED JALAPENO CHEDDAR BAGELS M A-30-7-8 A-30-7-7",
        )
        val rows = TextTableParser.parse(lines)
        assertEquals(5, rows.size)

        assertEquals("FFM 5PK SLICED SOURDOUGH BAGELS", rows[0].name)
        assertEquals("A-30-7-4", rows[0].location)

        assertEquals("BILL KNAPPS DUNKER NUTTY", rows[1].name)
        assertEquals("822910100", rows[1].orderCode)
        assertEquals("A-30-7-9", rows[1].location) // New ILC
        assertTrue(rows[1].upcValid)

        assertEquals("BILL KNAPPS DUNKER CHOCOLATE", rows[2].name)

        assertEquals("FFM BREAD CINNAMON 16.9 OZ", rows[3].name)
        assertEquals("16.9 OZ", rows[3].size)
        assertEquals("", rows[3].location) // deleted — no new location

        assertEquals("FFM 5PK UNSLICED JALAPENO CHEDDAR BAGELS", rows[4].name)
    }

    @Test fun creamCakeReportKeepsTrailingProductWords() {
        val rows = TextTableParser.parse(
            listOf(
                "4069764031 CAFE VALLEY CREME CAKE RING 7UP M A-35-1-5 A-35-1-7",
                "71373365678 FRESH FROM MEIJER CREME CAKE VARIETY M A-35-1-9 A-35-1-2",
            ),
        )
        assertEquals("CAFE VALLEY CREME CAKE RING 7UP", rows[0].name)
        assertEquals("A-35-1-7", rows[0].location)
        assertEquals("FRESH FROM MEIJER CREME CAKE VARIETY", rows[1].name)
    }

    @Test fun ignoresLinesWithoutAUpc() {
        assertEquals(emptyList<OrderRow>(), TextTableParser.parse(listOf("Page 1 of 2", "STORE 135 DEPT 53")))
    }
}
