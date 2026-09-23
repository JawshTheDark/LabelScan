package dev.jawsh.labelscan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jawsh.labelscan.parse.BarcodeEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/** A top bar without its own insets; the root Scaffold already pads for system bars. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Bar(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) = TopAppBar(
    title = title,
    navigationIcon = navigationIcon,
    actions = actions,
    windowInsets = WindowInsets(0, 0, 0, 0),
)

fun formatDate(t: Long): String =
    if (t <= 0) "" else SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(t)

fun formatDay(t: Long): String =
    if (t <= 0) "" else SimpleDateFormat("MMM d", Locale.getDefault()).format(t)

/**
 * The UPC drawn as a real UPC-A / EAN-13 barcode on a white card, so an
 * inventory scan gun can read it straight off the phone. Renders nothing for
 * codes that fail their check digit.
 */
@Composable
fun BarcodeView(code: String, modifier: Modifier = Modifier) {
    val modules = BarcodeEncoder.modules(code) ?: return
    Column(
        modifier
            .background(Color.White)
            .padding(horizontal = 12.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val quiet = 11 // modules of white margin per side
            val w = size.width / (BarcodeEncoder.MODULES + quiet * 2)
            for (i in modules.indices) {
                if (!modules[i]) continue
                val h = if (BarcodeEncoder.isGuard(i)) size.height else size.height * 0.88f
                drawRect(Color.Black, Offset((quiet + i) * w, 0f), Size(w, h))
            }
        }
        Text(code, color = Color.Black, fontFamily = FontFamily.Monospace, fontSize = 22.sp)
    }
}
