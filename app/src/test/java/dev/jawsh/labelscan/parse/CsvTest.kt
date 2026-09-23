package dev.jawsh.labelscan.parse

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {
    @Test fun roundTrips() {
        val rows = listOf(listOf("890497000030", "ACE \"BISTRO\", SOUR", "a\nb"), listOf("x", "", "z"))
        val text = rows.joinToString("\r\n") { Csv.row(it) } + "\r\n"
        assertEquals(rows, Csv.parse(text))
    }
}
