package dev.jawsh.labelscan

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.jawsh.labelscan.data.LabelDb
import dev.jawsh.labelscan.data.LabelRecognizer
import dev.jawsh.labelscan.data.Photos
import dev.jawsh.labelscan.data.PhotoKind
import dev.jawsh.labelscan.data.Product
import dev.jawsh.labelscan.data.ProductCsv
import dev.jawsh.labelscan.parse.Gtin
import dev.jawsh.labelscan.parse.LabelData
import dev.jawsh.labelscan.parse.OrderRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

sealed interface Screen {
    data object Library : Screen
    data object Scan : Screen
    data object ScanSheet : Screen
    data class Review(val label: LabelData, val photo: String) : Screen
    data class OrderReview(val rows: List<OrderRow>) : Screen
    data class Detail(val upc: String) : Screen
}

class AppViewModel(private val app: Application) : AndroidViewModel(app) {
    val db = LabelDb(app)
    private val recognizer = LabelRecognizer()

    var screen by mutableStateOf<Screen>(Screen.Library)
    var query by mutableStateOf("")
        private set
    var products by mutableStateOf<List<Product>>(emptyList())
        private set
    var total by mutableStateOf(0)
        private set
    var busy by mutableStateOf(false)
        private set
    /** Bumped after every write so detail screens reload. */
    var revision by mutableStateOf(0)
        private set

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    init {
        refresh()
    }

    fun onQuery(q: String) {
        query = q
        refresh()
    }

    private fun refresh() {
        val q = query
        viewModelScope.launch {
            val (list, count) = withContext(Dispatchers.IO) { db.search(q) to db.count() }
            if (q == query) products = list
            total = count
            revision++
        }
    }

    private fun say(msg: String) {
        messages.tryEmit(msg)
    }

    /** OCRs a captured or picked photo and opens the review screen. */
    fun process(bitmap: Bitmap) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val photo = withContext(Dispatchers.IO) { Photos.store(app, bitmap) }
                val label = recognizer.read(bitmap)
                if (label.score() == 0) say("Couldn't read the label — type the UPC or retake")
                screen = Screen.Review(label, photo)
            } catch (e: Exception) {
                say("Scan failed: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    fun processUri(uri: Uri) {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) { runCatching { Photos.load(app, uri) }.getOrNull() }
            if (bmp == null) say("Couldn't open that image") else process(bmp)
        }
    }

    fun save(edited: LabelData, photo: String, scanNext: Boolean) {
        val label = edited.copy(upc = completeUpc(edited.upc))
        viewModelScope.launch {
            val known = withContext(Dispatchers.IO) {
                val existed = db.product(label.upc) != null
                db.saveScan(label, photo)
                existed
            }
            say(if (known) "Updated ${label.name.ifBlank { label.upc }}" else "Added ${label.name.ifBlank { label.upc }}")
            // Plain Save lands on the item so more photos can be piled on; "next" goes back to the camera.
            screen = if (scanNext) Screen.Scan else Screen.Detail(label.upc)
            refresh()
        }
    }

    /** Reads a whole order-book page into reviewable catalog rows. */
    fun processOrderSheet(bitmap: Bitmap) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val rows = withContext(Dispatchers.Default) { recognizer.readOrderSheet(bitmap) }
                if (rows.isEmpty()) {
                    say("No rows found — fill the frame with the page, held upright")
                } else {
                    screen = Screen.OrderReview(rows)
                }
            } catch (e: Exception) {
                say("Couldn't read the sheet: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    fun processOrderSheetUri(uri: Uri) = processOrderSheetUris(listOf(uri))

    /** Reads one or more scanned pages (e.g. from the document scanner) and merges their rows. */
    fun processOrderSheetUris(uris: List<Uri>) {
        if (busy || uris.isEmpty()) return
        busy = true
        viewModelScope.launch {
            try {
                val all = mutableListOf<OrderRow>()
                for (uri in uris) {
                    val bmp = withContext(Dispatchers.IO) { runCatching { Photos.load(app, uri) }.getOrNull() } ?: continue
                    all += withContext(Dispatchers.Default) { recognizer.readOrderSheet(bmp) }
                }
                val byUpc = LinkedHashMap<String, OrderRow>()
                for (r in all) {
                    if (r.upc.isBlank()) continue
                    val e = byUpc[r.upc]
                    byUpc[r.upc] = if (e == null || (e.name.isBlank() && r.name.isNotBlank())) r else e
                }
                val rows = byUpc.values.toList()
                if (rows.isEmpty()) say("No rows found — try re-scanning the page") else screen = Screen.OrderReview(rows)
            } catch (e: Exception) {
                say("Couldn't read the sheet: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    fun saveOrderRows(products: List<Product>) {
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { db.importOrderRows(products) }
            say("Imported $n item${if (n == 1) "" else "s"}")
            screen = Screen.Library
            refresh()
        }
    }

    /** Attaches gallery images to a product, tagging their kind; a PLU shot fills a blank PLU. */
    fun addPhotoUris(upc: String, uris: List<Uri>, kind: PhotoKind) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) {
                var count = 0
                for (uri in uris) {
                    val bmp = runCatching { Photos.load(app, uri) }.getOrNull() ?: continue
                    db.addPhoto(upc, kind, Photos.store(app, bmp))
                    if (kind == PhotoKind.PLU) fillFromPlu(upc, bmp)
                    count++
                }
                count
            }
            say(if (n > 0) "Added $n photo${if (n == 1) "" else "s"}" else "Couldn't add those photos")
            refresh()
        }
    }

    fun addPhotoBitmap(upc: String, bitmap: Bitmap, kind: PhotoKind) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.addPhoto(upc, kind, Photos.store(app, bitmap))
                if (kind == PhotoKind.PLU) fillFromPlu(upc, bitmap)
            }
            say("Added ${kind.label} photo")
            refresh()
        }
    }

    /** Reads a PLU tag and fills the product's PLU/name if they're still blank. */
    private suspend fun fillFromPlu(upc: String, bitmap: Bitmap) {
        val label = runCatching { recognizer.read(bitmap) }.getOrNull() ?: return
        val p = db.product(upc) ?: return
        val updated = p.copy(
            plu = p.plu.ifBlank { label.plu },
            name = p.name.ifBlank { label.name },
        )
        if (updated != p) db.updateProduct(upc, updated)
    }

    fun removePhoto(id: Long) {
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) { db.deletePhoto(id) }
            path?.let { withContext(Dispatchers.IO) { File(it).delete() } }
            refresh()
        }
    }

    /** A typed-in UPC without its check digit (as case labels print it) gets one. */
    private fun completeUpc(upc: String): String =
        if (upc.length in 6..11) upc.padStart(11, '0').let { it + Gtin.checkDigit(it) } else upc

    fun discard(photo: String) {
        viewModelScope.launch(Dispatchers.IO) { File(photo).delete() }
        screen = Screen.Scan
    }

    fun update(oldUpc: String, product: Product) {
        viewModelScope.launch {
            val fixed = product.copy(upc = completeUpc(product.upc))
            withContext(Dispatchers.IO) { db.updateProduct(oldUpc, fixed) }
            screen = Screen.Detail(fixed.upc)
            refresh()
        }
    }

    fun delete(upc: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.deleteProduct(upc).forEach { File(it).delete() } }
            say("Deleted $upc")
            screen = Screen.Library
            refresh()
        }
    }

    /** Writes the whole repository to a CSV and returns a share intent for it. */
    suspend fun exportIntent(): Intent = withContext(Dispatchers.IO) {
        val dir = File(app.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(System.currentTimeMillis())
        val file = File(dir, "labelscan-$stamp.csv")
        file.writeText(ProductCsv.write(db.search("")))
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun import(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = app.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                    db.import(ProductCsv.read(text))
                }
            }
            say(result.fold({ "Imported $it UPCs" }, { "Import failed: ${it.message}" }))
            refresh()
        }
    }

    override fun onCleared() {
        recognizer.close()
        db.close()
    }
}
