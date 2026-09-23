package dev.jawsh.labelscan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.jawsh.labelscan.AppViewModel
import dev.jawsh.labelscan.Screen
import dev.jawsh.labelscan.data.Product

/** Editable fields for one order-sheet row; the include flag lives in a separate state list. */
private class RowDraft(
    var name: String,
    var upc: String,
    var size: String,
    val section: String,
    val codes: List<String>,
    val orderCode: String,
)

@Composable
fun OrderReviewScreen(vm: AppViewModel, review: Screen.OrderReview, modifier: Modifier) {
    val drafts = remember(review) {
        review.rows.map { RowDraft(it.name, it.upc, it.size, it.section, it.codes, it.orderCode) }
    }
    val include = remember(review) {
        mutableStateListOf<Boolean>().apply { addAll(review.rows.map { it.upcValid }) }
    }
    val chosen = include.count { it }
    val existing by androidx.compose.runtime.produceState(emptySet<String>(), review) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.db.existingUpcs(review.rows.map { it.upc }) }
    }

    fun toProducts(): List<Product> = drafts.filterIndexed { i, d -> include[i] && d.upc.length >= 6 }.map { d ->
        val notes = buildList {
            if (d.codes.isNotEmpty()) add("codes: " + d.codes.joinToString(" "))
            if (d.orderCode.isNotEmpty()) add("order#${d.orderCode}")
        }.joinToString(" | ")
        Product(
            upc = d.upc,
            name = d.name.trim(),
            category = d.section,
            size = d.size.trim(),
            dept = d.section.take(3).takeIf { it.length == 3 && it.all(Char::isDigit) }?.let { "D$it" } ?: "",
            notes = notes,
        )
    }

    Column(modifier.fillMaxSize()) {
        Bar(
            title = {
                Column {
                    Text("Order sheet")
                    Text("$chosen of ${drafts.size} selected", style = MaterialTheme.typography.labelMedium)
                }
            },
            navigationIcon = {
                IconButton(onClick = { vm.screen = Screen.Library }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel")
                }
            },
            actions = {
                TextButton(onClick = { for (i in include.indices) include[i] = true }) { Text("All") }
                TextButton(onClick = { for (i in include.indices) include[i] = false }) { Text("None") }
            },
        )
        Text(
            "UPCs came from the barcodes and are exact. Check the names, then import.",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
        )

        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
            itemsIndexed(drafts) { i, d ->
                RowEditor(d, include[i], review.rows[i].upc in existing) { include[i] = it }
                HorizontalDivider()
            }
        }
        Button(
            onClick = { vm.saveOrderRows(toProducts()) },
            enabled = chosen > 0,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) { Text("Import $chosen item${if (chosen == 1) "" else "s"}") }
    }
}

@Composable
private fun RowEditor(d: RowDraft, checked: Boolean, alreadyInCatalog: Boolean, onCheck: (Boolean) -> Unit) {
    var name by remember(d) { mutableStateOf(d.name) }
    var upc by remember(d) { mutableStateOf(d.upc) }
    var size by remember(d) { mutableStateOf(d.size) }

    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Checkbox(checked = checked, onCheckedChange = onCheck)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                name, { name = it; d.name = it },
                Modifier.fillMaxWidth(),
                label = { Text("Name") },
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    upc, { upc = it.filter(Char::isDigit); d.upc = upc },
                    Modifier.weight(2f),
                    label = { Text("UPC") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    singleLine = true,
                )
                OutlinedTextField(
                    size, { size = it; d.size = it },
                    Modifier.weight(1f),
                    label = { Text("Size") },
                    singleLine = true,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (d.section.isNotEmpty()) Text(d.section, style = MaterialTheme.typography.labelSmall)
                if (alreadyInCatalog) {
                    Text(
                        "• already in catalog — will fill blanks",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
