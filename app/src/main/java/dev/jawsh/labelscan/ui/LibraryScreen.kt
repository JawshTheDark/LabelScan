package dev.jawsh.labelscan.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jawsh.labelscan.AppViewModel
import dev.jawsh.labelscan.Screen
import dev.jawsh.labelscan.data.Product

@Composable
fun LibraryScreen(vm: AppViewModel, modifier: Modifier) {
    var menu by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::import)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let(vm::exportTo)
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Bar(
                title = {
                    Column {
                        Text("LabelScan")
                        Text("${vm.total} UPCs", style = MaterialTheme.typography.labelMedium)
                    }
                },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Scan order/report sheet") }, onClick = {
                            menu = false
                            vm.screen = Screen.ScanSheet
                        })
                        DropdownMenuItem(text = { Text("Export CSV") }, onClick = {
                            menu = false
                            exporter.launch(vm.exportFileName())
                        })
                        DropdownMenuItem(text = { Text("Import CSV") }, onClick = {
                            menu = false
                            importer.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel"))
                        })
                    }
                },
            )
            OutlinedTextField(
                value = vm.query,
                onValueChange = vm::onQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                placeholder = { Text("Name, UPC, item #, slot…") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (vm.query.isNotEmpty()) {
                        IconButton(onClick = { vm.onQuery("") }) { Icon(Icons.Filled.Clear, "Clear") }
                    }
                },
                singleLine = true,
            )
            if (vm.products.isEmpty()) {
                Text(
                    if (vm.query.isEmpty()) "No UPCs yet.\nScan a label, or ⋮ → Import order sheet."
                    else "Nothing matches \"${vm.query}\".",
                    Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                items(vm.products, key = { it.upc }) { p ->
                    ProductRow(p) { vm.screen = Screen.Detail(p.upc) }
                    HorizontalDivider()
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { vm.screen = Screen.Scan },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("Scan") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
    }
}

@Composable
private fun ProductRow(p: Product, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                p.name.ifBlank { "(no name)" },
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(p.upc, fontFamily = FontFamily.Monospace)
            val sub = listOfNotNull(
                p.itemNo.takeIf { it.isNotEmpty() }?.let { "ITM $it" },
                p.size.takeIf { it.isNotEmpty() },
                p.dept.takeIf { it.isNotEmpty() },
                p.plu.takeIf { it.isNotEmpty() }?.let { "PLU $it" },
                p.lastSlot.takeIf { it.isNotEmpty() },
            ).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("×${p.timesSeen}", style = MaterialTheme.typography.titleMedium)
            Text(formatDay(p.lastSeen), style = MaterialTheme.typography.bodySmall)
        }
    }
}
