package dev.jawsh.labelscan

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.jawsh.labelscan.ui.DetailScreen
import dev.jawsh.labelscan.ui.LibraryScreen
import dev.jawsh.labelscan.ui.ReviewScreen
import dev.jawsh.labelscan.ui.ScanScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { App(vm) } }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFFFFB300), secondary = Color(0xFFFFCA28))
        else -> lightColorScheme(primary = Color(0xFF8D5A00), secondary = Color(0xFFA66F00))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun App(vm: AppViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val screen = vm.screen
    BackHandler(enabled = screen != Screen.Library) {
        if (screen is Screen.Review) vm.discard(screen.photo) else vm.screen = Screen.Library
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        val m = Modifier.padding(pad)
        when (screen) {
            Screen.Library -> LibraryScreen(vm, m)
            Screen.Scan -> ScanScreen(vm, m)
            is Screen.Review -> ReviewScreen(vm, screen, m)
            is Screen.Detail -> DetailScreen(vm, screen.upc, m)
        }
    }
}
