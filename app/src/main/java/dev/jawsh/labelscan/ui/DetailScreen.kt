package dev.jawsh.labelscan.ui

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog

import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import dev.jawsh.labelscan.AppViewModel
import dev.jawsh.labelscan.Screen
import dev.jawsh.labelscan.data.Photo
import dev.jawsh.labelscan.data.PhotoKind
import dev.jawsh.labelscan.data.Photos
import dev.jawsh.labelscan.data.Product
import dev.jawsh.labelscan.data.Receipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private data class Detail(val product: Product?, val receipts: List<Receipt>, val photos: List<Photo>)

@Composable
fun DetailScreen(vm: AppViewModel, upc: String, modifier: Modifier) {
    val ctx = LocalContext.current
    val data by produceState<Detail?>(null, upc, vm.revision) {
        value = withContext(Dispatchers.IO) {
            Detail(vm.db.product(upc), vm.db.receipts(upc), vm.db.photos(upc))
        }
    }
    // After a restore, images arrive as metadata only; pull the bytes when the item is opened.
    androidx.compose.runtime.LaunchedEffect(upc) { vm.ensurePhotos(upc) }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var addKind by remember { mutableStateOf<PhotoKind?>(null) }
    var bigPhoto by remember { mutableStateOf<String?>(null) }
    var viewPhoto by remember { mutableStateOf<Photo?>(null) }

    // Attach flow: pick a kind, then a source.
    var pendingKind by remember { mutableStateOf(PhotoKind.PLU) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(12)) { uris ->
        if (uris.isNotEmpty()) vm.addPhotoUris(upc, uris, pendingKind)
    }
    var captureUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = captureUri
        if (ok && u != null) vm.addPhotoUris(upc, listOf(u), pendingKind)
    }
    fun launchCamera() {
        val dir = File(ctx.cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "cap_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        captureUri = uri
        camera.launch(uri)
    }

    MaxBrightness()

    val product = data?.product
    Column(modifier.fillMaxSize()) {
        Bar(
            title = { Text(product?.name?.ifBlank { null } ?: upc) },
            navigationIcon = {
                IconButton(onClick = { vm.screen = Screen.Library }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            },
            actions = {
                if (product != null && !editing) {
                    IconButton(onClick = { addKind = PhotoKind.PLU }) { Icon(Icons.Filled.Add, "Add photos") }
                    IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, "Edit") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
                }
            },
        )
        if (product == null) return@Column

        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (editing) {
                Editor(product, onCancel = { editing = false }) {
                    editing = false
                    vm.update(upc, it)
                }
            } else {
                BarcodeView(product.upc, Modifier.fillMaxWidth())
                if (product.name.isNotEmpty()) Text(product.name, style = MaterialTheme.typography.headlineSmall)
                Info("UPC", product.upc, mono = true)
                Info("Item #", product.itemNo)
                Info("Size", product.size)
                Info("Price", product.price)
                Info("Cost/oz", product.unitPrice)
                Info("Category", product.category)
                Info("Dept", product.dept)
                Info("PLU", product.plu)
                Info("Location", product.lastSlot)
                Info("Notes", product.notes)
                Info("Seen", "${product.timesSeen}× — first ${formatDate(product.firstSeen)}, last ${formatDate(product.lastSeen)}")
            }

            val photos = data?.photos.orEmpty()
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Photos", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { addKind = PhotoKind.PLU }) { Text("Add") }
            }
            if (photos.isEmpty()) {
                Text(
                    "No photos yet. Add a PLU tag, packaging shot, or ILC label.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                PhotoGallery(photos) { viewPhoto = it }
            }

            val receipts = data?.receipts.orEmpty()
            if (receipts.isNotEmpty()) {
                HorizontalDivider()
                Text("Cases received", style = MaterialTheme.typography.titleMedium)
                receipts.forEach { r -> ReceiptRow(r) { bigPhoto = r.photo } }
            }
        }
    }

    addKind?.let { current ->
        AddPhotoDialog(
            kind = current,
            onKind = { addKind = it },
            onDismiss = { addKind = null },
            onCamera = { pendingKind = current; addKind = null; launchCamera() },
            onGallery = {
                pendingKind = current
                addKind = null
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete $upc?") },
            text = { Text("Removes this UPC, its case history and photos.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(upc) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    bigPhoto?.let { path -> FullImage(path, onDismiss = { bigPhoto = null }) }
    viewPhoto?.let { p ->
        FullImage(p.path, onDismiss = { viewPhoto = null }) {
            vm.removePhoto(p.id)
            viewPhoto = null
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhotoGallery(photos: List<Photo>, onOpen: (Photo) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        photos.forEach { p ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val thumb by produceState<Bitmap?>(null, p.path) {
                    value = withContext(Dispatchers.IO) { Photos.thumbnail(p.path, 300) }
                }
                Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))) {
                    thumb?.let {
                        Image(
                            it.asImageBitmap(), p.kind.label,
                            Modifier.fillMaxSize().clickable { onOpen(p) },
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                Text(p.kind.label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddPhotoDialog(
    kind: PhotoKind,
    onKind: (PhotoKind) -> Unit,
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add photos") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("What do these show?", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhotoKind.entries.forEach { k ->
                        FilterChip(selected = k == kind, onClick = { onKind(k) }, label = { Text(k.label) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onCamera) { Text("Camera") } },
        dismissButton = { TextButton(onClick = onGallery) { Text("Gallery") } },
    )
}

@Composable
private fun FullImage(path: String, onDismiss: () -> Unit, onDelete: (() -> Unit)? = null) {
    Dialog(onDismissRequest = onDismiss) {
        Column {
            val bmp by produceState<Bitmap?>(null, path) {
                value = withContext(Dispatchers.IO) { Photos.thumbnail(path, 1600) }
            }
            bmp?.let {
                Image(
                    it.asImageBitmap(), "Photo",
                    Modifier.fillMaxWidth().clickable(onClick = onDismiss),
                    contentScale = ContentScale.Fit,
                )
            }
            if (onDelete != null) {
                Row(Modifier.fillMaxWidth().background(Color(0xCC000000)).padding(8.dp)) {
                    TextButton(onClick = onDelete) { Text("Delete", color = Color.White) }
                }
            }
        }
    }
}

/** Full brightness while a barcode is on screen, so scan guns read it reliably. */
@Composable
private fun MaxBrightness() {
    val window = (LocalContext.current as? Activity)?.window ?: return
    DisposableEffect(window) {
        val old = window.attributes.screenBrightness
        window.attributes = window.attributes.apply { screenBrightness = 1f }
        onDispose { window.attributes = window.attributes.apply { screenBrightness = old } }
    }
}

@Composable
private fun Info(label: String, value: String, mono: Boolean = false) {
    if (value.isEmpty()) return
    Row {
        Text(label, Modifier.weight(0.3f), style = MaterialTheme.typography.labelLarge)
        Text(value, Modifier.weight(0.7f), fontFamily = if (mono) FontFamily.Monospace else null)
    }
}

@Composable
private fun ReceiptRow(r: Receipt, onPhoto: () -> Unit) {
    val thumb by produceState<Bitmap?>(null, r.photo) { value = withContext(Dispatchers.IO) { Photos.thumbnail(r.photo, 200) } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        thumb?.let {
            Image(it.asImageBitmap(), "Label photo", Modifier.size(64.dp).clickable(onClick = onPhoto), contentScale = ContentScale.Crop)
        }
        Column {
            Text(formatDate(r.scannedAt))
            Text(
                listOfNotNull(
                    r.caseNo?.let { "case $it of ${r.caseTotal}" },
                    r.slot.takeIf { it.isNotEmpty() },
                    r.door.takeIf { it.isNotEmpty() }?.let { "door $it" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Editor(p: Product, onCancel: () -> Unit, onSave: (Product) -> Unit) {
    var e by remember(p) { mutableStateOf(p) }
    OutlinedTextField(
        e.upc, { e = e.copy(upc = it.filter(Char::isDigit)) }, Modifier.fillMaxWidth(),
        label = { Text("UPC") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    Field("Name", e.name.uppercase()) { e = e.copy(name = it.uppercase()) }
    Field("Item #", e.itemNo, number = true) { e = e.copy(itemNo = it) }
    Field("Size", e.size) { e = e.copy(size = it) }
    Field("Price", e.price) { e = e.copy(price = it) }
    Field("Cost/oz", e.unitPrice) { e = e.copy(unitPrice = it) }
    Field("Category", e.category) { e = e.copy(category = it) }
    Field("Dept", e.dept) { e = e.copy(dept = it) }
    Field("PLU", e.plu, number = true) { e = e.copy(plu = it) }
    Field("Location", e.lastSlot) { e = e.copy(lastSlot = it) }
    OutlinedTextField(e.notes, { e = e.copy(notes = it) }, Modifier.fillMaxWidth(), label = { Text("Notes") })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCancel, Modifier.weight(1f)) { Text("Cancel") }
        Button(onClick = { onSave(e) }, Modifier.weight(1f), enabled = e.upc.length >= 6) { Text("Save") }
    }
}
