package dev.jawsh.labelscan.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real labels, transcribed one OCR line per printed line. */
class LabelParserTest {
    private val ace = listOf(
        "C8-30-S", "3 of 7", "ACE BISTRO LOAF SOUR", "DF804",
        "DOUGH 21 OZ", "M012/Q001 09/22", "ITM870628 21 OZ", "ASG#1029801153",
        "UPC#89049700030", "BKY", "8202230", "02326886 08/19",
        "K053", "AD", "A-MF-36-06-004", "135 5470932",
    )

    private val foldNHold = listOf(
        "69-17-03", "8 of 13", "FOLD N HOLD LABEL HL", "CTP", "DF090",
        "DR 100/CS -90", "M001/Q001", "04/27", "ITM992006 100/CASE", "ASG#1567440143",
        "UPC#70882082329", "122202540", "K020", "135 1361248", "No Primary",
    )

    /** Different label layout: no ITM/UPC prefixes. */
    private val brookie = listOf(
        "223401", "1 of 1", "FFM BROOKIE PLATTER", "DF089", "9CT", "M008/Q001", "09/20",
        "054850", "8400", "ASG#27643210", "FROZ", "71928307439", "K053", "AD", "NEW", "135 2080579",
    )

    private val cakeDome = listOf(
        "CASE", "CAKE DOME ROUND DOUBLE 10", "A43 05/07/26", "DF802", "0/CS -93/802", "M001/Q001",
        "ITM119056", "ASG#219321280", "UPC#88692611461", "BKY", "CP-102552 02315855 04/04",
        "K053", "135 9302422", "W-MF-01-04-026",
    )

    @Test fun aceBistro() {
        val d = LabelParser.parse(ace, listOf(ScannedBarcode("1355470932", isProductCode = false)))
        assertEquals("ACE BISTRO LOAF SOUR", d.name)
        assertEquals("DOUGH", d.category)
        // Printed without its check digit; Kroger lists this loaf as 0089049700030.
        assertEquals("890497000306", d.upc)
        assertEquals("89049700030", d.upcPrinted)
        assertEquals(UpcSource.PRINTED_COMPLETED, d.upcSource)
        assertEquals("870628", d.itemNo)
        assertEquals("21 OZ", d.size)
        assertEquals("BKY", d.dept)
        assertEquals("A-MF-36-06-004", d.slot)
        assertEquals("C8-30-S", d.door)
        assertEquals(3, d.caseNo)
        assertEquals(7, d.caseTotal)
        assertEquals("1029801153", d.asg)
        assertEquals("1355470932", d.caseId)
        assertTrue(d.isComplete)
    }

    @Test fun foldNHold() {
        val d = LabelParser.parse(foldNHold)
        assertEquals("FOLD N HOLD LABEL HL", d.name)
        assertEquals("708820823297", d.upc)
        assertEquals("992006", d.itemNo)
        assertEquals("100/CS", d.size)
        assertEquals(8, d.caseNo)
        assertEquals(13, d.caseTotal)
        assertEquals("1567440143", d.asg)
    }

    @Test fun brookieWithoutPrefixes() {
        val d = LabelParser.parse(brookie)
        assertEquals("FFM BROOKIE PLATTER", d.name)
        assertEquals("719283074393", d.upc)
        assertEquals(UpcSource.PRINTED_COMPLETED, d.upcSource)
        assertEquals("9 CT", d.size)
        assertEquals("FROZ", d.dept)
        assertEquals(1, d.caseNo)
        assertEquals("27643210", d.asg)
    }

    @Test fun cakeDome() {
        val d = LabelParser.parse(cakeDome)
        assertEquals("CAKE DOME ROUND DOUBLE 10", d.name)
        assertEquals("886926114614", d.upc)
        assertEquals("119056", d.itemNo)
        assertEquals("BKY", d.dept)
        assertEquals("W-MF-01-04-026", d.slot)
        assertEquals("", d.size)
    }

    @Test fun mergedLinesAndLetterDigitConfusion() {
        val noisy = listOf(
            "C8-30-S 3 of 7 ACE BISTRO LOAF SOUR",
            "DOUGH 21 0Z M012/Q001 09/22",
            "ITM87O628 21 OZ ASG#1O29801153",
            "UPC#89O497OOO3O BKY",
            "A - MF - 36 - 06 - 004",
        )
        val d = LabelParser.parse(noisy)
        assertEquals("ACE BISTRO LOAF SOUR", d.name)
        assertEquals("890497000306", d.upc)
        assertEquals("870628", d.itemNo)
        assertEquals("21 OZ", d.size)
        assertEquals("1029801153", d.asg)
        assertEquals("A-MF-36-06-004", d.slot)
        assertEquals(3, d.caseNo)
    }

    @Test fun mergedLeadingNumbersAreNotPartOfTheName() {
        val d = LabelParser.parse(listOf("223401 1 of 1 FFM BROOKIE PLATTER", "CASE CAKE", "69-17-03 8 of 13 X"))
        assertEquals("FFM BROOKIE PLATTER", d.name)
    }

    @Test fun printedUpcWithCheckDigitIsVerified() {
        val good = LabelParser.parse(listOf("UPC#890497000306"))
        assertEquals("890497000306", good.upc)
        assertEquals(UpcSource.PRINTED_CHECKED, good.upcSource)
    }

    @Test fun leadingZerosDroppedFromPrintedUpc() {
        // Retail 041250999348 printed without check digit and leading zero.
        assertEquals("041250999348", LabelParser.parse(listOf("UPC#4125099934")).upc)
    }

    @Test fun retailBarcodeBeatsOcr() {
        val d = LabelParser.parse(listOf("UPC#89049700031"), listOf(ScannedBarcode("890497000306", isProductCode = true)))
        assertEquals("890497000306", d.upc)
        assertEquals(UpcSource.BARCODE, d.upcSource)
    }

    @Test fun retailLabelUsesHeadlineTypeForName() {
        val lines = listOf(
            OcrLine("REG PRICE $ 5.99", 40f),
            OcrLine("FFM TURNOVERS", 46f),
            OcrLine("APPLE 4CT", 45f),
            OcrLine("Sell By .09/25/26", 36f),
            OcrLine("Ingredients: Dough: Enriched Wheat Flour (Wheat Flour,", 18f),
            OcrLine("Malted Barley Flour, Niacin, Ascorbic Acid, Reduced Iron,", 18f),
            OcrLine("Thiamine Mononitrate, Riboflavin, Enzyme, and Folic Acid),", 18f),
            OcrLine("Water, Sugar, Vital Wheat Gluten, Salt. Apple Filling: Apples,", 18f),
            OcrLine("Natural Flavor, Gellan Gum, Nutmeg, Salt. Topping: Crystal", 18f),
            OcrLine("Contains: Wheat, Soy", 18f),
        )
        val d = LabelParser.parseLines(lines)
        assertEquals("FFM TURNOVERS APPLE", d.name)
        assertEquals("4 CT", d.size)
    }

    @Test fun nutritionPanelGivesNoNameButPluAndBarcode() {
        val lines = listOf(
            "Nutrition Facts", "Serving Size 1 Piece(100G)", "Servings Per Container 4",
            "Calories from Fat 210", "Total Fat 23.0g", "Saturated Fat 11.0g", "Trans Fat 0.0g",
            "Cholesterol 0mg", "Sodium 310mg", "Total Carbohydrate 52g", "Dietary Fiber 1g",
            "Sugars 23g", "Protein 5g", "Vitamin A 0%", "Calcium 0%",
            "* Not a significant source of calories from: Trans Fat.",
            "Dist. by Meijer Distribution Inc. Grand Rapids, MI 49544", "09/21/26 06:15",
            "PLU #: 299", "135 - 16", "0041250 999348",
        )
        val d = LabelParser.parse(lines, listOf(ScannedBarcode("041250999348", isProductCode = true)))
        assertEquals("", d.name)
        assertEquals("299", d.plu)
        assertEquals("041250999348", d.upc)
    }

    @Test fun nameWrapsFromHeaderBarToNextLine() {
        // FREDERIKS label: brand in the header bar, product description wraps below.
        val lines = listOf(
            "B7-37-U 11 of 12 FREDERIKS BY MEIJER",
            "BUTTER MINI CROISSA M010/Q001 09/19",
            "ITM454940 9.9 OZ",
            "ASG#1092361037",
            "UPC#70882081649",
            "BKY",
        )
        val d = LabelParser.parse(lines)
        assertEquals("FREDERIKS BY MEIJER BUTTER MINI CROISSA", d.name)
        assertEquals("708820816497", d.upc)
        assertEquals("9.9 OZ", d.size)
    }

    @Test fun caseCountOnSameSizeLineIsNotAppendedToName() {
        // Headline wrap must not swallow a "1 of 1" printed at the same size.
        val lines = listOf(
            OcrLine("SNACK PACK MINI", 46f),
            OcrLine("1 of 1", 45f),
            OcrLine("W-ML-30-07-020", 45f),
            OcrLine("UPC#70882081649", 18f),
        )
        assertEquals("SNACK PACK MINI", LabelParser.parseLines(lines).name)
    }

    @Test fun caseCountMergedIntoNameLineIsStripped() {
        assertEquals("SNACK PACK MINI", LabelParser.parse(listOf("SNACK PACK MINI 1 of 1")).name)
    }

    @Test fun boxHandlingInstructionsAreNotTheName() {
        // Label tiny/upside-down; big carton instructions dominate the frame.
        val lines = listOf(
            "MOVE FROM FREEZER, PLACE PRODUCT ON LINED SHEET",
            "AT IN OVEN AT 375F FOR 14 MINUTES BEFORE FINISHING",
            "THIS SIDE UP",
            "0 of 0 DONUT FENCE PLAN 96CT",
            "ITM600976 3 OZ",
            "ASG#219704845",
        )
        val d = LabelParser.parse(lines)
        assertEquals("DONUT FENCE PLAN", d.name)
        assertEquals("3 OZ", d.size)
        assertEquals("219704845", d.asg)
    }

    @Test fun withoutANameLineTheNameStaysBlankNotInstructions() {
        val lines = listOf(
            "MOVE FROM FREEZER, PLACE PRODUCT ON LINED SHEET",
            "PREHEAT OVEN TO 375 DEGREES FOR 14 MINUTES",
            "ITM600976 3 OZ",
        )
        assertEquals("", LabelParser.parse(lines).name)
    }

    @Test fun emptyInputGivesEmptyData() {
        val d = LabelParser.parse(emptyList())
        assertEquals("", d.upc)
        assertNull(d.caseNo)
        assertEquals(0, d.score())
    }
}
