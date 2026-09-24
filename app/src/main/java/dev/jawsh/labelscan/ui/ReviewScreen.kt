package dev.jawsh.labelscan.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.jawsh.labelscan.AppViewModel
import dev.jawsh.labelscan.Screen
import dev.jawsh.labelscan.data.Photos
import dev.jawsh.labelscan.data.Product
import dev.jawsh.labelscan.data.Receipt
import dev.jawsh.labelscan.parse.Gtin
import dev.jawsh.labelscan.parse.LabelData
import dev.jawsh.labelscan.parse.UpcSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ReviewScreen(vm: AppViewModel, review: Screen.Review, modifier: Modifier) {
    var label by remember(review) { mutableStateOf(review.label) }
    var showRaw by remember { mutableStateOf(false) }

    val photo by produceState<android.graphics.Bitmap?>(null, review.photo) {
        value = withContext(Dispatchers.IO) { Photos.thumbnail(review.photo, 1200) }
    }
    val known by produceState<Product?>(null, label.upc) {
        value = if (Gtin.isValid(label.upc)) withContext(Dispatchers.IO) { vm.db.product(label.upc) } else null
    }
    val repeat by produceState<Receipt?>(null, label.caseId) {
        value = withContext(Dispatchers.IO) { vm.db.receiptByCaseId(label.caseId) }
    }

    val upcOk = label.upc.length >= 6 && label.upc.all { it.isDigit() }

    Column(modifier.fillMaxSize()) {
        Bar(
            title = { Text("Check label") },
            navigationIcon = {
                IconButton(onClick = { vm.discard(review.photo) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retake") }
            },
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            photo?.let {
                Image(
                    it.asImageBitmap(), "Label photo",
                    Modifier.fillMaxWidth().heightIn(max = 260.dp),
                    contentScale = ContentScale.Fit,
                )
            }

            repeat?.let {
                Notice("This exact case was already scanned ${formatDate(it.scannedAt)}. Saving will count it again.", warn = true)
            }
            known?.let {
                Notice("Already in your repository as \"${it.name.ifBlank { "(no name)" }}\" — seen ${it.timesSeen}×. Saving adds this case.")
            }

            OutlinedTextField(
                label.upc, { label = label.copy(upc = it.filter(Char::isDigit)) },
                Modifier.fillMaxWidth(),
                label = { Text("UPC") },
                textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                isError = label.upc.length > 11 && !label.upcValid,
                supportingText = { Text(upcHint(label, edited = label.upc != review.label.upc)) },
            )
            Field("Name", label.name.uppercase(), placeholder = known?.name) { label = label.copy(name = it.uppercase()) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Item #", label.itemNo, Modifier.weight(1f), number = true) { label = label.copy(itemNo = it) }
                Field("Size", label.size, Modifier.weight(1f)) { label = label.copy(size = it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Category", label.category, Modifier.weight(1f)) { label = label.copy(category = it) }
                Field("Dept", label.dept, Modifier.weight(1f)) { label = label.copy(dept = it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Slot", label.slot, Modifier.weight(2f)) { label = label.copy(slot = it) }
                Field("PLU", label.plu, Modifier.weight(1f), number = true) { label = label.copy(plu = it) }
            }

            val extra = listOfNotNull(
                label.caseNo?.let { "Case $it of ${label.caseTotal}" },
                label.door.takeIf { it.isNotEmpty() }?.let { "Door $it" },
                label.asg.takeIf { it.isNotEmpty() }?.let { "ASG $it" },
                label.caseId.takeIf { it.isNotEmpty() }?.let { "Case ID $it" },
            )
            if (extra.isNotEmpty()) Text(extra.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium)

            TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "Hide OCR text" else "Show OCR text") }
            if (showRaw) Text(label.rawText.ifEmpty { "(nothing recognised)" }, fontFamily = FontFamily.Monospace)
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.discard(review.photo) }, Modifier.weight(1f)) { Text("Retake") }
            OutlinedButton(onClick = { vm.save(label, review.photo, scanNext = false) }, Modifier.weight(1f), enabled = upcOk) {
                Text("Save")
            }
            Button(onClick = { vm.save(label, review.photo, scanNext = true) }, Modifier.weight(1.3f), enabled = upcOk) {
                Text("Save + next")
            }
        }
    }
}

private fun upcHint(label: LabelData, edited: Boolean): String = when {
    label.upc.isEmpty() -> "Not found — type it in"
    label.upc.length <= 11 -> "Check digit will be added on save"
    edited -> if (label.upcValid) "✓ Check digit OK" else "⚠ Check digit doesn't match"
    else -> when (label.upcSource) {
        UpcSource.BARCODE -> "✓ Read from the barcode"
        UpcSource.PRINTED_CHECKED -> "✓ Check digit OK"
        UpcSource.PRINTED_COMPLETED ->
            "Label prints ${label.upcPrinted} (no check digit) — compare those digits with the photo"
        UpcSource.PRINTED_BAD, UpcSource.NONE -> "⚠ Check digit doesn't match — compare with the photo"
    }
}

@Composable
private fun Notice(text: String, warn: Boolean = false) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (warn) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(text, Modifier.padding(12.dp)) }
}

@Composable
fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    placeholder: String? = null,
    number: Boolean = false,
    onChange: (String) -> Unit,
) = OutlinedTextField(
    value, onChange, modifier,
    label = { Text(label) },
    placeholder = placeholder?.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
    singleLine = true,
    keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
)
