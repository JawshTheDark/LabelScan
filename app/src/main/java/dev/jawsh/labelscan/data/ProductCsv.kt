package dev.jawsh.labelscan.data

import dev.jawsh.labelscan.parse.Csv
import dev.jawsh.labelscan.parse.Gtin
import java.text.SimpleDateFormat
import java.util.Locale

/** Product list <-> CSV, for backups and for pasting into inventory spreadsheets. */
object ProductCsv {
    private val HEADER = listOf(
        "upc", "name", "category", "item_no", "size", "dept", "plu",
        "last_slot", "notes", "times_seen", "first_seen", "last_seen",
    )

    private fun dateFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    fun write(products: List<Product>): String {
        val fmt = dateFormat()
        fun date(t: Long) = if (t > 0) fmt.format(t) else ""
        return buildString {
            append(Csv.row(HEADER)).append("\r\n")
            for (p in products) {
                append(
                    Csv.row(
                        listOf(
                            p.upc, p.name, p.category, p.itemNo, p.size, p.dept, p.plu, p.lastSlot,
                            p.notes, p.timesSeen.toString(), date(p.firstSeen), date(p.lastSeen),
                        )
                    )
                ).append("\r\n")
            }
        }
    }

    /** Reads a CSV with an `upc` header column; other columns are optional and matched by name. */
    fun read(text: String): List<Product> {
        val rows = Csv.parse(text.removePrefix("﻿"))
        if (rows.isEmpty()) return emptyList()
        val cols = rows[0].map { it.trim().lowercase() }
        val upcCol = cols.indexOf("upc")
        require(upcCol >= 0) { "CSV needs a 'upc' column" }
        val fmt = dateFormat()
        return rows.drop(1).mapNotNull { r ->
            fun col(name: String) = cols.indexOf(name).let { if (it in r.indices) r[it].trim() else "" }
            fun date(name: String) = col(name).takeIf { it.isNotEmpty() }
                ?.let { runCatching { fmt.parse(it)?.time }.getOrNull() } ?: 0L
            val upc = Gtin.restoreLeadingZeros(col("upc").filter { it.isDigit() })
            if (upc.isEmpty()) return@mapNotNull null
            Product(
                upc = upc,
                name = col("name"),
                category = col("category"),
                itemNo = col("item_no"),
                size = col("size"),
                dept = col("dept"),
                plu = col("plu"),
                lastSlot = col("last_slot"),
                notes = col("notes"),
                timesSeen = col("times_seen").toIntOrNull() ?: 0,
                firstSeen = date("first_seen"),
                lastSeen = date("last_seen"),
            )
        }
    }
}
