package dev.jawsh.labelscan.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderSheetParserTest {
    @Test fun orderCodeBecomesValidUpc() {
        assertEquals("719283027276", OrderSheetParser.upcFromOrderCode("071928302727"))
        assertEquals("094776033522", OrderSheetParser.upcFromOrderCode("009477603352"))
        // Already a valid 12-digit UPC-A: kept unchanged.
        assertEquals("094776140480", OrderSheetParser.upcFromOrderCode("094776140480"))
        assertTrue(Gtin.isValid(OrderSheetParser.upcFromOrderCode("071373388902")))
    }

    /** Two rows laid out like the DEPT 53 page: description + codes left, barcode far right. */
    @Test fun parsesRowsAnchoredOnBarcodes() {
        val texts = listOf(
            TextBox("305 BREAD INGT SUM BAKER", 400, 650, 500, 34),
            // Row 1
            TextBox("SPREAD HERBED GARLIC", 400, 780, 380, 34),
            TextBox("10184017", 400, 835, 150, 28),
            TextBox("288579", 760, 780, 120, 28),
            TextBox("519027", 760, 835, 120, 28),
            TextBox("15 LB", 940, 785, 90, 28),
            TextBox("1 4 20", 1360, 785, 90, 28),
            TextBox("ACT", 1470, 785, 70, 28),
            TextBox("310 CAKE INGT SUM DRY BA", 400, 900, 520, 34),
            // Row 2
            TextBox("PECAN PIECES MEDIUM 5 LBS", 400, 980, 470, 34),
            TextBox("530042", 400, 1035, 120, 28),
            TextBox("054491", 760, 980, 120, 28),
            TextBox("3540651", 760, 1035, 130, 28),
            TextBox("5 LB", 940, 985, 80, 28),
            TextBox("ACT", 1470, 985, 70, 28),
        )
        val barcodes = listOf(
            BarcodeBox("071928302727", 1080, 770, 240, 70),
            BarcodeBox("094776140480", 1080, 970, 240, 70),
        )

        val rows = OrderSheetParser.parse(texts, barcodes)
        assertEquals(2, rows.size)

        val garlic = rows[0]
        assertEquals("SPREAD HERBED GARLIC", garlic.name)
        assertEquals("719283027276", garlic.upc)
        assertTrue(garlic.upcValid)
        assertEquals("15 LB", garlic.size)
        assertEquals("305 BREAD INGT SUM BAKER", garlic.section)
        assertTrue(garlic.codes.containsAll(listOf("10184017", "288579", "519027")))

        val pecan = rows[1]
        assertEquals("PECAN PIECES MEDIUM 5 LBS", pecan.name)
        assertEquals("094776140480", pecan.upc)
        assertEquals("5 LB", pecan.size)
        assertEquals("310 CAKE INGT SUM DRY BA", pecan.section)
    }

    @Test fun noBarcodesNoRows() {
        val texts = listOf(TextBox("SOMETHING", 10, 10, 100, 20))
        assertEquals(emptyList<OrderRow>(), OrderSheetParser.parse(texts, emptyList()))
    }
}
