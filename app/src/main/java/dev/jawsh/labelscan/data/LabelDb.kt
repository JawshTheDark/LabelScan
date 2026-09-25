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
    val price: String = "",
    val unitPrice: String = "",
    val notes: String = "",
    val timesSeen: Int = 0,
    val firstSeen: Long = 0,
    val lastSeen: Long = 0,
)

/** What a stored photo shows, so the gallery can label and group shots. */
enum class PhotoKind(val label: String) {
    ILC("ILC"), PLU("PLU"), PACKAGING("Packaging"), ORDER("Order sheet"), OTHER("Other");

    companion object {
        fun from(name: String?) = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

/** An extra picture attached to a product (PLU tag, packaging, ILC, …). */
data class Photo(
    val id: Long,
    val upc: String,
    val kind: PhotoKind,
    val path: String,
    val note: String,
    val createdAt: Long,
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

class LabelDb(context: Context) : SQLiteOpenHelper(context, "labelscan.db", null, 4) {

    override fun onCreate(db: SQLiteDatabase) {
        createPhotoTable(db)
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
                price TEXT NOT NULL DEFAULT '',
                unit_price TEXT NOT NULL DEFAULT '',
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

    private fun createPhotoTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE photo(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                upc TEXT NOT NULL,
                kind TEXT NOT NULL DEFAULT 'OTHER',
                path TEXT NOT NULL,
                note TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX photo_upc ON photo(upc)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE product ADD COLUMN plu TEXT NOT NULL DEFAULT ''")
            completeCheckDigits(db)
        }
        if (oldVersion < 3) createPhotoTable(db)
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE product ADD COLUMN price TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE product ADD COLUMN unit_price TEXT NOT NULL DEFAULT ''")
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

    /** How the library list is ordered. */
    enum class Sort(val label: String, val sql: String) {
        RECENT("Recent", "last_seen DESC"),
        // Unnamed items sink to the bottom instead of leading an A–Z list.
        NAME("Name A–Z", "CASE WHEN name = '' THEN 1 ELSE 0 END, name COLLATE NOCASE, upc"),
        UPC("UPC", "upc"),
    }

    /** Every whitespace-separated term must match some field (name, UPC, item #, slot, ...). */
    fun search(query: String, sort: Sort = Sort.RECENT): List<Product> {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val fields = listOf("upc", "name", "category", "item_no", "size", "dept", "plu", "last_slot", "notes")
        val where = terms.joinToString(" AND ") { "(" + fields.joinToString(" OR ") { f -> "$f LIKE ?" } + ")" }
        val args = terms.flatMap { t -> List(fields.size) { "%$t%" } }.toTypedArray()
        val sql = "SELECT * FROM product" + (if (terms.isEmpty()) "" else " WHERE $where") +
            " ORDER BY ${sort.sql}"
        return readableDatabase.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(c.toProduct()) } }
    }

    fun product(upc: String): Product? =
        readableDatabase.rawQuery("SELECT * FROM product WHERE upc = ?", arrayOf(upc))
            .use { if (it.moveToFirst()) it.toProduct() else null }

    /** Of the given UPCs, those already in the repository (so an import merges, not duplicates). */
    fun existingUpcs(upcs: Collection<String>): Set<String> {
        val list = upcs.filter { it.isNotBlank() }.distinct()
        if (list.isEmpty()) return emptySet()
        val marks = list.joinToString(",") { "?" }
        return readableDatabase.rawQuery("SELECT upc FROM product WHERE upc IN ($marks)", list.toTypedArray())
            .use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
    }

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
                price = label.price.ifBlank { old?.price ?: "" },
                unitPrice = label.unitPrice.ifBlank { old?.unitPrice ?: "" },
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
                db.update("photo", ContentValues().apply { put("upc", p.upc) }, "upc = ?", arrayOf(oldUpc))
            }
            db.insertWithOnConflict("product", null, merged.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Extra photos attached to a product, newest first. */
    fun photos(upc: String): List<Photo> =
        readableDatabase.rawQuery("SELECT * FROM photo WHERE upc = ? ORDER BY created_at DESC", arrayOf(upc))
            .use { c -> buildList { while (c.moveToNext()) add(c.toPhoto()) } }

    fun addPhoto(upc: String, kind: PhotoKind, path: String, note: String = "", now: Long = System.currentTimeMillis()) {
        writableDatabase.insert("photo", null, ContentValues().apply {
            put("upc", upc)
            put("kind", kind.name)
            put("path", path)
            put("note", note)
            put("created_at", now)
        })
    }

    /** Deletes one attached photo and returns its file path so the caller can remove it. */
    fun deletePhoto(id: Long): String? {
        val path = readableDatabase.rawQuery("SELECT path FROM photo WHERE id = ?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) it.getString(0) else null }
        writableDatabase.delete("photo", "id = ?", arrayOf(id.toString()))
        return path
    }

    /** Deletes a product with its receipts and photos; returns the file paths that are now unused. */
    fun deleteProduct(upc: String): List<String> {
        val files = (receipts(upc).map { it.photo } + photos(upc).map { it.path }).filter { it.isNotEmpty() }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("receipt", "upc = ?", arrayOf(upc))
            db.delete("photo", "upc = ?", arrayOf(upc))
            db.delete("product", "upc = ?", arrayOf(upc))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return files
    }

    /** Adds catalog rows read from an order sheet; fills blanks on any that already exist. */
    fun importOrderRows(rows: List<Product>): Int = import(rows)

    /**
     * Upserts catalog rows keyed by UPC — never a second row for the same UPC.
     * Existing data is preserved: a field is only written when it is currently
     * blank, so re-importing the same UPC across pages fills gaps without
     * clobbering good values. Notes accumulate (codes/order#s union), and
     * receipt counts and dates are left as they were.
     */
    fun import(products: List<Product>): Int {
        // Fold same-UPC duplicates within this batch together first.
        val byUpc = LinkedHashMap<String, Product>()
        for (p in products) {
            if (p.upc.isBlank()) continue
            byUpc[p.upc] = byUpc[p.upc]?.let { fillBlanks(it, p) } ?: p
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((upc, p) in byUpc) {
                val merged = product(upc)?.let { fillBlanks(it, p) } ?: p
                db.insertWithOnConflict("product", null, merged.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return byUpc.size
    }

    /** Kept-existing merge: [base] wins on every non-blank field; [extra] only fills gaps. */
    private fun fillBlanks(base: Product, extra: Product) = base.copy(
        name = base.name.ifBlank { extra.name },
        category = base.category.ifBlank { extra.category },
        itemNo = base.itemNo.ifBlank { extra.itemNo },
        size = base.size.ifBlank { extra.size },
        dept = base.dept.ifBlank { extra.dept },
        plu = base.plu.ifBlank { extra.plu },
        lastSlot = base.lastSlot.ifBlank { extra.lastSlot },
        price = base.price.ifBlank { extra.price },
        unitPrice = base.unitPrice.ifBlank { extra.unitPrice },
        notes = mergeNotes(base.notes, extra.notes),
        timesSeen = maxOf(base.timesSeen, extra.timesSeen),
        firstSeen = listOf(base.firstSeen, extra.firstSeen).filter { it > 0 }.minOrNull() ?: 0,
        lastSeen = maxOf(base.lastSeen, extra.lastSeen),
    )

    /** Unions two notes strings on their " | " segments, dropping duplicates. */
    private fun mergeNotes(a: String, b: String): String {
        val seen = LinkedHashSet<String>()
        (a.split(" | ") + b.split(" | ")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { seen += it }
        return seen.joinToString(" | ")
    }

    private fun Product.toValues() = ContentValues().apply {
        put("upc", upc)
        put("name", name.uppercase()) // names are always stored capitalized
        put("category", category)
        put("item_no", itemNo)
        put("size", size)
        put("dept", dept)
        put("plu", plu)
        put("last_slot", lastSlot)
        put("price", price)
        put("unit_price", unitPrice)
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
        price = str("price"),
        unitPrice = str("unit_price"),
        notes = str("notes"),
        timesSeen = long("times_seen").toInt(),
        firstSeen = long("first_seen"),
        lastSeen = long("last_seen"),
    )

    private fun Cursor.toPhoto() = Photo(
        id = long("id"),
        upc = str("upc"),
        kind = PhotoKind.from(str("kind")),
        path = str("path"),
        note = str("note"),
        createdAt = long("created_at"),
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
