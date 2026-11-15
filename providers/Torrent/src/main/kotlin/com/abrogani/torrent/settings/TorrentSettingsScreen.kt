package com.abrogani.torrent.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.abrogani.torrent.engine.EngineState
import com.abrogani.torrent.engine.TorrentEngine
import com.flixclusive.provider.extensions.getBoolAsFlow
import com.flixclusive.provider.extensions.getInt
import com.flixclusive.provider.extensions.setBool
import com.flixclusive.provider.extensions.setInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun TorrentSettingsScreen(
    settings: DataStore<Preferences>,
    engine: TorrentEngine,
    isInterceptorInstalled: Boolean,
) {
    val scope = rememberCoroutineScope()

    val state by engine.state.collectAsState()
    val intercept by remember(settings) {
        settings.getBoolAsFlow(INTERCEPT_KEY, DEFAULT_INTERCEPT)
    }.collectAsState(initial = DEFAULT_INTERCEPT)

    var portText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(settings) {
        portText = settings.getInt(PORT_KEY, DEFAULT_PORT).toString()
    }

    fun run(
        progress: String,
        done: String,
        action: suspend () -> Unit,
    ) {
        busy = true
        message = progress
        scope.launch {
            message = try {
                action()
                done
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                "Failed: ${e.message ?: e.javaClass.simpleName}"
            }
            busy = false
        }
    }

    val installed = when (state) {
        EngineState.NotInstalled, is EngineState.Installing -> false
        is EngineState.Failed -> engine.hasEngineFiles
        EngineState.Stopped, is EngineState.Running -> true
    }
    val port = portText.toIntOrNull()
    val portValid = port?.isValidPort() == true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Torrent engine", style = MaterialTheme.typography.titleMedium)
        Text(text = state.describe(engine.engineVersion), style = MaterialTheme.typography.bodyMedium)

        (state as? EngineState.Installing)?.let {
            if (it.total > 0) {
                LinearProgressIndicator(
                    progress = { (it.downloaded.toFloat() / it.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (installed) {
                OutlinedButton(
                    onClick = { run("Removing...", "Removed") { engine.remove() } },
                    enabled = !busy,
                ) { Text("Remove engine") }
                OutlinedButton(
                    onClick = { run("Clearing...", "Cleared active torrents") { engine.dropAll() } },
                    enabled = !busy && state is EngineState.Running,
                ) { Text("Clear torrents") }
            } else {
                Button(
                    onClick = { run("Downloading...", "Installed") { engine.install() } },
                    enabled = !busy && state !is EngineState.Installing,
                ) { Text("Download engine") }
            }
        }

        if (message.isNotEmpty()) {
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }

        Text(text = "Links", style = MaterialTheme.typography.titleMedium)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Play torrent links from all providers", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (isInterceptorInstalled) {
                        "Torrent links from your other providers play through the engine."
                    } else {
                        "Unavailable: this version of the app isn't supported. Torrent links won't be changed."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = intercept,
                enabled = isInterceptorInstalled,
                onCheckedChange = { scope.launch { settings.setBool(INTERCEPT_KEY, it) } },
            )
        }

        OutlinedTextField(
            value = portText,
            onValueChange = { text ->
                portText = text.filter(Char::isDigit).take(5)
                portText.toIntOrNull()
                    ?.takeIf { it.isValidPort() }
                    ?.let { scope.launch { settings.setInt(PORT_KEY, it) } }
            },
            label = { Text("Engine port") },
            supportingText = {
                Text(
                    if (portValid) {
                        "Takes effect the next time the app starts."
                    } else {
                        "Use a port between 1024 and 65535."
                    },
                )
            },
            isError = !portValid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun EngineState.describe(version: String): String =
    when (this) {
        EngineState.NotInstalled -> "Not installed. Download the engine (about 5 MB) to play torrent links."
        is EngineState.Installing -> "Downloading engine $version..."
        EngineState.Stopped -> "Installed ($version). It starts when a torrent link is found."
        is EngineState.Running -> "Running on port $port ($version)"
        is EngineState.Failed -> "Error: $message"
    }
