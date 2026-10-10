package com.abrogani.streamplay.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.abrogani.streamplay.loader.Cs3PluginLoader
import com.flixclusive.provider.extensions.getBoolAsFlow
import com.flixclusive.provider.extensions.setBool
import kotlinx.coroutines.launch

@Composable
internal fun UpdateScreen(
    settings: DataStore<Preferences>,
    loader: Cs3PluginLoader,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val autoUpdate by remember(settings) {
        settings.getBoolAsFlow(AUTO_UPDATE_KEY, true)
    }.collectAsState(initial = true)

    var installed by remember { mutableStateOf<Int?>(null) }
    var latest by remember { mutableStateOf<Int?>(null) }
    var updateAvailable by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    val check: () -> Unit = {
        scope.launch {
            busy = true
            runCatching { loader.checkForUpdate(context) }
                .onSuccess {
                    installed = it.installed
                    latest = it.latest
                    updateAvailable = it.available
                    message = if (it.available) "Update available" else "Up to date"
                }
                .onFailure { message = "Could not check for updates: ${it.message}" }
            busy = false
        }
    }

    val update: () -> Unit = {
        scope.launch {
            busy = true
            message = "Updating..."
            runCatching { loader.update(context) }
                .onSuccess { updated ->
                    installed = loader.installedVersion(context)
                    updateAvailable = !updated && updateAvailable
                    message = if (updated) "Updated" else "The new build could not be loaded; kept the current one"
                }
                .onFailure { message = "Update failed: ${it.message}" }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        installed = loader.installedVersion(context)
        check()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Installed version: ${installed?.let { "v$it" } ?: "not installed"}",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "Latest version: ${latest?.let { "v$it" } ?: "unknown"}",
            style = MaterialTheme.typography.bodyMedium
        )
        if (message.isNotEmpty()) {
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "Update automatically", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = autoUpdate,
                onCheckedChange = { scope.launch { settings.setBool(AUTO_UPDATE_KEY, it) } }
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = check, enabled = !busy) {
                Text("Check for updates")
            }
            Button(onClick = update, enabled = !busy && updateAvailable) {
                Text("Update now")
            }
        }
    }
}
