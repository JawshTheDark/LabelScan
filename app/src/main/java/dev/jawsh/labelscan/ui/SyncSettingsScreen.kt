package dev.jawsh.labelscan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.jawsh.labelscan.AppViewModel
import dev.jawsh.labelscan.Screen

@Composable
fun SyncSettingsScreen(vm: AppViewModel, modifier: Modifier) {
    val prefs = vm.syncPrefs
    var url by remember { mutableStateOf(prefs.baseUrl) }
    var email by remember { mutableStateOf(prefs.email) }
    var password by remember { mutableStateOf(prefs.password) }

    Column(modifier.fillMaxSize()) {
        Bar(
            title = { Text("Sync settings") },
            navigationIcon = {
                IconButton(onClick = { vm.screen = Screen.Library }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Back up your catalog and photos to your own server so they survive reinstalls " +
                    "and new phones. Use the https:// address of your LabelScan backend.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                url, { url = it }, Modifier.fillMaxWidth(),
                label = { Text("Server URL") },
                placeholder = { Text("https://labelscan.irc.so") },
                singleLine = true,
            )
            OutlinedTextField(
                email, { email = it }, Modifier.fillMaxWidth(),
                label = { Text("Email") }, singleLine = true,
            )
            OutlinedTextField(
                password, { password = it }, Modifier.fillMaxWidth(),
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            Button(
                onClick = { vm.signIn(url, email, password) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Test & sign in") }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-sync", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Push every scan and edit to the server automatically.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = vm.syncEnabled, onCheckedChange = { vm.toggleSync(it) })
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { vm.backupNow() }, modifier = Modifier.weight(1f)) {
                    Text("Back up now")
                }
                OutlinedButton(onClick = { vm.restoreFromServer() }, modifier = Modifier.weight(1f)) {
                    Text("Restore")
                }
            }
            Text(
                "Restore pulls the catalog back; photos download as you open each item.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
