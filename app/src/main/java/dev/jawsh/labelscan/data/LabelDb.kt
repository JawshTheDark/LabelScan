package dev.jawsh.labelscan.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.jawsh.labelscan.parse.Gtin
import dev.jawsh.labelscan.parse.LabelData

/** One UPC in the repository — what inventory lookups are about. */
data class Product(
    val upc: String,
    val name: String = "",
    val category: String = "",
    val itemNo: String = "",
    val size: String = "",
    val dept: String = "",
    val plu: String = "",
    val lastSlot: String = "",
    val notes: String = "",
    val timesSeen: Int = 0,
    val firstSeen: Long = 0,
    val lastSeen: Long = 0,
)

/** One physical case that came in, i.e. one scanned label. */
data class Receipt(
    val id: Long,
    val upc: String,
    val caseId: String,
    val caseNo: Int?,
    val caseTotal: Int?,
    val slot: String,
    val door: String,
    val asg: String,
    val photo: String,
    val scannedAt: Long,
)

class LabelDb(context: Context) : SQLiteOpenHelper(context, "labelscan.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE product(
                upc TEXT PRIMARY KEY,
                name TEXT NOT NULL DEFAULT '',
                category TEXT NOT NULL DEFAULT '',
                item_no TEXT NOT NULL DEFAULT '',
                size TEXT NOT NULL DEFAULT '',
                dept TEXT NOT NULL DEFAULT '',
                plu TEXT NOT NULL DEFAULT '',
                last_slot TEXT NOT NULL DEFAULT '',
                notes TEXT NOT NULL DEFAULT '',
                times_seen INTEGER NOT NULL DEFAULT 0,
                first_seen INTEGER NOT NULL,
                last_seen INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE receipt(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                upc TEXT NOT NULL,
                case_id TEXT NOT NULL DEFAULT '',
                case_no INTEGER,
                case_total INTEGER,
                slot TEXT NOT NULL DEFAULT '',
                door TEXT NOT NULL DEFAULT '',
                asg TEXT NOT NULL DEFAULT '',
                photo TEXT NOT NULL DEFAULT '',
                raw_text TEXT NOT NULL DEFAULT '',
                scanned_at INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX receipt_upc ON receipt(upc)")
        db.execSQL("CREATE INDEX receipt_case ON receipt(case_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE product ADD COLUMN plu TEXT NOT NULL DEFAULT ''")
            completeCheckDigits(db)
        }
    }

    /**
     * 0.1.0 stored case-label UPCs as printed — 11 digits, no check digit.
     * Give them their check digit so they match real barcodes.
     */
    private fun completeCheckDigits(db: SQLiteDatabase) {
        val short = db.rawQuery("SELECT upc FROM product WHERE length(upc) = 11", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        for (old in short) {
            if (!old.all { it.isDigit() }) continue
            val full = old + Gtin.checkDigit(old)
            val taken = db.rawQuery("SELECT 1 FROM product WHERE upc = ?", arrayOf(full)).use { it.moveToFirst() }
            if (taken) continue
            db.execSQL("UPDATE product SET upc = ? WHERE upc = ?", arrayOf(full, old))
            db.execSQL("UPDATE receipt SET upc = ? WHERE upc = ?", arrayOf(full, old))
        }
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM product", null)
        .use { it.moveToFirst(); it.getInt(0) }

    /** Every whitespace-separated term must match some field (name, UPC, item #, slot, ...). */
    fun search(query: String): List<Product> {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val fields = listOf("upc", "name", "category", "item_no", "size", "dept", "plu", "last_slot", "notes")
        val where = terms.joinToString(" AND ") { "(" + fields.joinToString(" OR ") { f -> "$f LIKE ?" } + ")" }
        val args = terms.flatMap { t -> List(fields.size) { "%$t%" } }.toTypedArray()
        val sql = "SELECT * FROM product" + (if (terms.isEmpty()) "" else " WHERE $where") +
            " ORDER BY last_seen DESC"
        return readableDatabase.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(c.toProduct()) } }
    }

    fun product(upc: String): Product? =
        readableDatabase.rawQuery("SELECT * FROM product WHERE upc = ?", arrayOf(upc))
            .use { if (it.moveToFirst()) it.toProduct() else null }

    fun receipts(upc: String): List<Receipt> =
        readableDatabase.rawQuery("SELECT * FROM receipt WHERE upc = ? ORDER BY scanned_at DESC", arrayOf(upc))
            .use { c -> buildList { while (c.moveToNext()) add(c.toReceipt()) } }

    /** The earlier scan of this exact case, if the label's case barcode was seen before. */
    fun receiptByCaseId(caseId: String): Receipt? {
        if (caseId.isBlank()) return null
        return readableDatabase.rawQuery("SELECT * FROM receipt WHERE case_id = ? LIMIT 1", arrayOf(caseId))
            .use { if (it.moveToFirst()) it.toReceipt() else null }
    }

    /** Records a received case and creates or refreshes its product. */
    fun saveScan(label: LabelData, photo: String, now: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val old = product(label.upc)
            val merged = Product(
                upc = label.upc,
                name = label.name.ifBlank { old?.name ?: "" },
                category = label.category.ifBlank { old?.category ?: "" },
                itemNo = label.itemNo.ifBlank { old?.itemNo ?: "" },
                size = label.size.ifBlank { old?.size ?: "" },
                dept = label.dept.ifBlank { old?.dept ?: "" },
                plu = label.plu.ifBlank { old?.plu ?: "" },
                lastSlot = label.slot.ifBlank { old?.lastSlot ?: "" },
                notes = old?.notes ?: "",
                timesSeen = (old?.timesSeen ?: 0) + 1,
                firstSeen = old?.firstSeen ?: now,
                lastSeen = now,
            )
            db.insertWithOnConflict("product", null, merged.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            db.insert("receipt", null, ContentValues().apply {
                put("upc", label.upc)
                put("case_id", label.caseId)
                put("case_no", label.caseNo)
                put("case_total", label.caseTotal)
                put("slot", label.slot)
                put("door", label.door)
                put("asg", label.asg)
                put("photo", photo)
                put("raw_text", label.rawText)
                put("scanned_at", now)
            })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Saves edits; changing the UPC carries the receipts along. */
    fun updateProduct(oldUpc: String, p: Product) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            var merged = p
            if (oldUpc != p.upc) {
                // Fixing a misread UPC onto one that already exists folds the two together.
                product(p.upc)?.let { other ->
                    merged = p.copy(
                        timesSeen = p.timesSeen + other.timesSeen,
                        firstSeen = minOf(p.firstSeen, other.firstSeen),
                        lastSeen = maxOf(p.lastSeen, other.lastSeen),
                    )
                }
                db.delete("product", "upc = ?", arrayOf(oldUpc))
                db.update("receipt", ContentValues().apply { put("upc", p.upc) }, "upc = ?", arrayOf(oldUpc))
            }
            db.insertWithOnConflict("product", null, merged.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Deletes a product and its receipts; returns the photo paths that are now unused. */
    fun deleteProduct(upc: String): List<String> {
        val photos = receipts(upc).map { it.photo }.filter { it.isNotEmpty() }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("receipt", "upc = ?", arrayOf(upc))
            db.delete("product", "upc = ?", arrayOf(upc))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return photos
    }

    /** Merges imported rows: non-blank imported text wins, counts and dates widen. */
    fun import(products: List<Product>): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (p in products) {
                val old = product(p.upc)
                val merged = if (old == null) p else Product(
                    upc = p.upc,
                    name = p.name.ifBlank { old.name },
                    category = p.category.ifBlank { old.category },
                    itemNo = p.itemNo.ifBlank { old.itemNo },
                    size = p.size.ifBlank { old.size },
                    dept = p.dept.ifBlank { old.dept },
                    plu = p.plu.ifBlank { old.plu },
                    lastSlot = p.lastSlot.ifBlank { old.lastSlot },
                    notes = p.notes.ifBlank { old.notes },
                    timesSeen = maxOf(p.timesSeen, old.timesSeen),
                    firstSeen = listOf(p.firstSeen, old.firstSeen).filter { it > 0 }.minOrNull() ?: 0,
                    lastSeen = maxOf(p.lastSeen, old.lastSeen),
                )
                db.insertWithOnConflict("product", null, merged.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return products.size
    }

    private fun Product.toValues() = ContentValues().apply {
        put("upc", upc)
        put("name", name)
        put("category", category)
        put("item_no", itemNo)
        put("size", size)
        put("dept", dept)
        put("plu", plu)
        put("last_slot", lastSlot)
        put("notes", notes)
        put("times_seen", timesSeen)
        put("first_seen", firstSeen)
        put("last_seen", lastSeen)
    }

    private fun Cursor.str(col: String) = getString(getColumnIndexOrThrow(col)) ?: ""
    private fun Cursor.long(col: String) = getLong(getColumnIndexOrThrow(col))
    private fun Cursor.intOrNull(col: String) =
        getColumnIndexOrThrow(col).let { if (isNull(it)) null else getInt(it) }

    private fun Cursor.toProduct() = Product(
        upc = str("upc"),
        name = str("name"),
        category = str("category"),
        itemNo = str("item_no"),
        size = str("size"),
        dept = str("dept"),
        plu = str("plu"),
        lastSlot = str("last_slot"),
        notes = str("notes"),
        timesSeen = long("times_seen").toInt(),
        firstSeen = long("first_seen"),
        lastSeen = long("last_seen"),
    )

    private fun Cursor.toReceipt() = Receipt(
        id = long("id"),
        upc = str("upc"),
        caseId = str("case_id"),
        caseNo = intOrNull("case_no"),
        caseTotal = intOrNull("case_total"),
        slot = str("slot"),
        door = str("door"),
        asg = str("asg"),
        photo = str("photo"),
        scannedAt = long("scanned_at"),
    )
}
