package dev.jawsh.labelscan.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BarcodeEncoderTest {
    private fun bits(code: String) =
        BarcodeEncoder.modules(code)!!.joinToString("") { if (it) "1" else "0" }

    // Reference patterns generated with python-barcode.
    @Test fun upcA() = assertEquals(UPC_890497000306, bits("890497000306"))
    @Test fun ean13() = assertEquals(EAN_4006381333931, bits("4006381333931"))
    @Test fun rejectsBadChecksum() = assertNull(BarcodeEncoder.modules("890497000307"))

    companion object {
        const val UPC_890497000306 = "10101101110001011000110101000110001011011101101010111001011100101110010100001011100101010000101"
        const val EAN_4006381333931 = "10100011010100111010111101111010001001011001101010100001010000101000010111010010000101100110101"
    }
}
