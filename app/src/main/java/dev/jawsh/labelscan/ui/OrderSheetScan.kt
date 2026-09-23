package dev.jawsh.labelscan.ui

import android.app.Activity
import android.app.Activity.RESULT_OK
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import dev.jawsh.labelscan.AppViewModel
import dev.jawsh.labelscan.Screen

/**
 * Captures order/throwaway sheets with Google's document scanner (edge detect,
 * de-skew, contrast cleanup, multi-page) — the portable stand-in for Samsung's
 * camera "scan document" — then feeds the flattened pages to the OCR pipeline.
 */
@Composable
fun OrderSheetScan(vm: AppViewModel, modifier: Modifier) {
    val ctx = LocalContext.current
    var launchError by remember { mutableStateOf<String?>(null) }

    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val pages = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages.orEmpty()
            val uris = pages.mapNotNull { it.imageUri }
            if (uris.isEmpty()) vm.messages.tryEmit("No pages scanned") else vm.processOrderSheetUris(uris)
        } else if (!vm.busy) {
            vm.screen = Screen.Library // user backed out of the scanner
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) vm.processOrderSheetUris(uris)
    }

    fun launchScanner() {
        val activity = ctx as? Activity ?: return
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(30)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
            .addOnSuccessListener { sender ->
                launchError = null
                scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { launchError = it.message ?: "Document scanner unavailable" }
    }

    // Open the scanner straight away; if it isn't available, offer a fallback.
    LaunchedEffect(Unit) { if (!vm.busy) launchScanner() }

    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (vm.busy) {
            CircularProgressIndicator()
            Text("Reading pages…", Modifier.padding(top = 16.dp))
            return@Column
        }
        Text(
            "Scan an order book or throwaway report",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            "The scanner finds the page edges, flattens and sharpens it, and lets you " +
                "capture several pages in a row. Then every row is read at once.",
            Modifier.padding(vertical = 12.dp).widthIn(max = 420.dp),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        launchError?.let {
            Text(
                "Scanner unavailable ($it). Use a photo instead.",
                Modifier.padding(bottom = 8.dp),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        Button(onClick = { launchScanner() }, Modifier.padding(top = 8.dp)) { Text("Scan pages") }
        OutlinedButton(
            onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            Modifier.padding(top = 8.dp),
        ) { Text("Pick from gallery") }
        OutlinedButton(onClick = { vm.screen = Screen.Library }, Modifier.padding(top = 8.dp)) { Text("Cancel") }
    }
}
