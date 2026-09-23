package dev.jawsh.labelscan.ui

import android.app.Activity
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.jawsh.labelscan.AppViewModel
import dev.jawsh.labelscan.Screen
import dev.jawsh.labelscan.data.Photos
import dev.jawsh.labelscan.data.Product
import dev.jawsh.labelscan.data.Receipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DetailScreen(vm: AppViewModel, upc: String, modifier: Modifier) {
    val data by produceState<Pair<Product?, List<Receipt>>?>(null, upc, vm.revision) {
        value = withContext(Dispatchers.IO) { vm.db.product(upc) to vm.db.receipts(upc) }
    }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var bigPhoto by remember { mutableStateOf<String?>(null) }

    MaxBrightness()

    val product = data?.first
    Column(modifier.fillMaxSize()) {
        Bar(
            title = { Text(product?.name?.ifBlank { null } ?: upc) },
            navigationIcon = {
                IconButton(onClick = { vm.screen = Screen.Library }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            },
            actions = {
                if (product != null && !editing) {
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
                Info("Category", product.category)
                Info("Dept", product.dept)
                Info("PLU", product.plu)
                Info("Last slot", product.lastSlot)
                Info("Notes", product.notes)
                Info("Seen", "${product.timesSeen}× — first ${formatDate(product.firstSeen)}, last ${formatDate(product.lastSeen)}")
            }

            val receipts = data?.second.orEmpty()
            if (receipts.isNotEmpty()) {
                HorizontalDivider()
                Text("Cases received", style = MaterialTheme.typography.titleMedium)
                receipts.forEach { r -> ReceiptRow(r) { bigPhoto = r.photo } }
            }
        }
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
    bigPhoto?.let { path ->
        Dialog(onDismissRequest = { bigPhoto = null }) {
            val bmp by produceState<Bitmap?>(null, path) { value = withContext(Dispatchers.IO) { Photos.thumbnail(path, 1600) } }
            bmp?.let {
                Image(it.asImageBitmap(), "Label photo", Modifier.fillMaxWidth().clickable { bigPhoto = null }, contentScale = ContentScale.Fit)
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
    Field("Name", e.name) { e = e.copy(name = it) }
    Field("Item #", e.itemNo, number = true) { e = e.copy(itemNo = it) }
    Field("Size", e.size) { e = e.copy(size = it) }
    Field("Category", e.category) { e = e.copy(category = it) }
    Field("Dept", e.dept) { e = e.copy(dept = it) }
    Field("PLU", e.plu, number = true) { e = e.copy(plu = it) }
    Field("Last slot", e.lastSlot) { e = e.copy(lastSlot = it) }
    OutlinedTextField(e.notes, { e = e.copy(notes = it) }, Modifier.fillMaxWidth(), label = { Text("Notes") })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCancel, Modifier.weight(1f)) { Text("Cancel") }
        Button(onClick = { onSave(e) }, Modifier.weight(1f), enabled = e.upc.length >= 6) { Text("Save") }
    }
}
