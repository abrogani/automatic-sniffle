package com.abrogani.mytorrentclient.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import com.abrogani.mytorrentclient.engine.EngineSettings
import com.abrogani.mytorrentclient.engine.EngineState
import com.abrogani.mytorrentclient.engine.TorrentEngine
import com.flixclusive.provider.extensions.getBoolAsFlow
import com.flixclusive.provider.extensions.getInt
import com.flixclusive.provider.extensions.setBool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun TorrentSettingsScreen(
    settings: DataStore<Preferences>,
    engine: TorrentEngine,
    isInterceptorInstalled: () -> Boolean,
    proxyPort: () -> Int?,
    onPortChange: (Int) -> Unit,
    backgroundScope: CoroutineScope,
) {
    val state by engine.state.collectAsState()
    val intercept by remember(settings) {
        settings.getBoolAsFlow(INTERCEPT_KEY, DEFAULT_INTERCEPT)
    }.collectAsState(initial = DEFAULT_INTERCEPT)

    var interceptorInstalled by remember { mutableStateOf(isInterceptorInstalled()) }
    var runningProxyPort by remember { mutableStateOf(proxyPort()) }
    LaunchedEffect(Unit) {
        while (true) {
            interceptorInstalled = isInterceptorInstalled()
            runningProxyPort = proxyPort()
            delay(STATUS_REFRESH_MILLIS)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EngineSection(engine = engine, state = state, backgroundScope = backgroundScope)

        HorizontalDivider()

        LinksSection(
            settings = settings,
            intercept = intercept,
            interceptorInstalled = interceptorInstalled,
            runningProxyPort = runningProxyPort,
            onPortChange = onPortChange,
        )

        HorizontalDivider()

        EngineSettingsSection(engine = engine, running = state is EngineState.Running)
    }
}

@Composable
private fun EngineSection(
    engine: TorrentEngine,
    state: EngineState,
    backgroundScope: CoroutineScope,
) {
    val installed = when (state) {
        EngineState.NotInstalled, is EngineState.Installing -> false
        is EngineState.Failed -> engine.hasEngineFiles
        EngineState.Stopped, is EngineState.Running -> true
    }

    SectionTitle("Torrent engine")
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

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    fun run(action: suspend () -> String) {
        busy = true
        message = ""
        backgroundScope.launch {
            message = try {
                action()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                e.message ?: e.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }

    val running = state is EngineState.Running

    if (installed) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = {
                    run {
                        when (val dropped = engine.dropAll()) {
                            0 -> "No torrents were active."
                            1 -> "Stopped 1 torrent."
                            else -> "Stopped $dropped torrents."
                        }
                    }
                },
                enabled = !busy && running,
            ) { Text("Stop all torrents") }
            OutlinedButton(
                onClick = { run { engine.stop(); "Engine stopped. It starts again when you play a torrent link." } },
                enabled = !busy && running,
            ) { Text("Stop engine") }
        }
        OutlinedButton(
            onClick = { run { engine.remove(); "Engine removed." } },
            enabled = !busy,
        ) { Text("Remove engine") }
    } else {
        Button(
            onClick = { backgroundScope.launch { engine.install() } },
            enabled = state !is EngineState.Installing,
        ) { Text("Download engine") }
    }

    if (message.isNotEmpty()) {
        Text(text = message, style = MaterialTheme.typography.bodyMedium)
    }

    Text(
        text = "While it runs, the engine's own port is reachable from other devices on your network, and it has no " +
            "password. They could see or stop what it is playing. It only starts when you play a torrent link. " +
            "Stop it here when you're done.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun LinksSection(
    settings: DataStore<Preferences>,
    intercept: Boolean,
    interceptorInstalled: Boolean,
    runningProxyPort: Int?,
    onPortChange: (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var portText by remember { mutableStateOf("") }

    LaunchedEffect(settings) {
        portText = settings.getInt(PORT_KEY, DEFAULT_PORT).toString()
    }

    SectionTitle("Links")

    SwitchRow(
        title = "Play torrent links from all providers",
        summary = if (interceptorInstalled) {
            "Torrent links from your other providers play through the engine."
        } else {
            "Unavailable: this version of the app isn't supported. Torrent links won't be changed."
        },
        checked = intercept,
        enabled = interceptorInstalled,
        onCheckedChange = { scope.launch { settings.setBool(INTERCEPT_KEY, it) } },
    )

    val port = portText.toIntOrNull()
    val portValid = port?.isValidPort() == true
    OutlinedTextField(
        value = portText,
        onValueChange = { text ->
            portText = text.filter(Char::isDigit).take(5)
            portText.toIntOrNull()
                ?.takeIf { it.isValidPort() }
                ?.let(onPortChange)
        },
        label = { Text("Link port") },
        supportingText = {
            Text(
                when {
                    !portValid -> "Use a port between 1024 and 65535."
                    runningProxyPort == null -> "Port $port is taken by another app. Pick another one."
                    runningProxyPort != port -> "Switching to $port..."
                    else -> "Only reachable from this device. Torrent links found earlier stop working when you change it."
                },
            )
        },
        isError = !portValid || runningProxyPort == null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EngineSettingsSection(
    engine: TorrentEngine,
    running: Boolean,
) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<EngineSettings?>(null) }
    var saved by remember { mutableStateOf<EngineSettings?>(null) }
    var trackersText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(engine) {
        val loaded = engine.loadSettings()
        saved = loaded
        draft = loaded
        trackersText = loaded.trackers.joinToString("\n")
    }

    SectionTitle("Engine settings")

    val current = draft ?: run {
        Text("Loading...", style = MaterialTheme.typography.bodyMedium)
        return
    }
    val edited = current.copy(trackers = trackersText.lines().map(String::trim).filter(String::isNotEmpty))
    val errors = edited.errors()
    val update: (EngineSettings) -> Unit = { draft = it }

    fun runAction(
        done: String,
        action: suspend () -> Unit,
    ) {
        busy = true
        message = ""
        scope.launch {
            message = try {
                action()
                done
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                e.message ?: e.javaClass.simpleName
            }
            busy = false
        }
    }

    Text(
        text = if (running) {
            "Applying settings restarts every active torrent, so anything playing from a torrent is interrupted."
        } else {
            "Settings are applied when the engine next starts."
        },
        style = MaterialTheme.typography.bodySmall,
    )

    SubsectionTitle("Cache")
    NumberField("Cache size per torrent (MB)", current.cacheSizeMb, "32 to 2048. Kept in memory unless stored on disk.") {
        update(current.copy(cacheSizeMb = it))
    }
    NumberField("Read-ahead (%)", current.readAheadPercent, "Share of the cache kept ahead of playback, 5 to 100.") {
        update(current.copy(readAheadPercent = it))
    }
    NumberField("Preload (%)", current.preloadPercent, "Share of the cache filled before playback starts, 0 to 100.") {
        update(current.copy(preloadPercent = it))
    }
    SwitchRow(
        title = "Store cache on disk",
        summary = "Uses app storage instead of memory. Still limited to the cache size per torrent.",
        checked = current.useDisk,
        onCheckedChange = { update(current.copy(useDisk = it)) },
    )
    SwitchRow(
        title = "Delete disk cache when a torrent stops",
        summary = null,
        checked = current.removeCacheOnDrop,
        enabled = current.useDisk,
        onCheckedChange = { update(current.copy(removeCacheOnDrop = it)) },
    )
    NumberField("Stop idle torrents after (seconds)", current.disconnectTimeoutSeconds, "1 to 3600.") {
        update(current.copy(disconnectTimeoutSeconds = it))
    }

    SubsectionTitle("Connections")
    NumberField("Connections per torrent", current.connectionsLimit, "1 to 200.") {
        update(current.copy(connectionsLimit = it))
    }
    NumberField("Download limit (KiB/s)", current.downloadRateLimitKib, "0 = unlimited.") {
        update(current.copy(downloadRateLimitKib = it))
    }
    NumberField("Upload limit (KiB/s)", current.uploadRateLimitKib, "0 = unlimited.") {
        update(current.copy(uploadRateLimitKib = it))
    }
    NumberField("Peer port", current.peersListenPort, "0 = random, or 1024 to 65535.") {
        update(current.copy(peersListenPort = it))
    }
    SwitchRow("Disable uploading", "Saves data, but peers may share less with you.", current.disableUpload) {
        update(current.copy(disableUpload = it))
    }
    SwitchRow("Require encryption", "Only connects to peers that encrypt, so fewer peers.", current.forceEncrypt) {
        update(current.copy(forceEncrypt = it))
    }
    SwitchRow("Enable IPv6", null, current.enableIpv6) { update(current.copy(enableIpv6 = it)) }
    SwitchRow("Disable TCP", null, current.disableTcp) { update(current.copy(disableTcp = it)) }
    SwitchRow("Disable uTP", null, current.disableUtp) { update(current.copy(disableUtp = it)) }
    SwitchRow("Disable UPnP port forwarding", null, current.disableUpnp) { update(current.copy(disableUpnp = it)) }
    SwitchRow("Disable DHT", "Magnet links without trackers need DHT to find peers.", current.disableDht) {
        update(current.copy(disableDht = it))
    }
    SwitchRow("Disable peer exchange (PEX)", null, current.disablePex) { update(current.copy(disablePex = it)) }

    SubsectionTitle("Trackers")
    RETRACKER_MODES.forEachIndexed { mode, label ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = current.retrackersMode == mode, onClick = { update(current.copy(retrackersMode = mode)) }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = current.retrackersMode == mode, onClick = { update(current.copy(retrackersMode = mode)) })
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
    OutlinedTextField(
        value = trackersText,
        onValueChange = { trackersText = it },
        label = { Text("Extra trackers") },
        supportingText = { Text("One announce URL per line. Used when adding or replacing trackers.") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
    )

    SubsectionTitle("Diagnostics")
    SwitchRow("Debug logging", "Very verbose; only useful when reporting a problem.", current.enableDebug) {
        update(current.copy(enableDebug = it))
    }

    errors.forEach {
        Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (message.isNotEmpty()) {
        Text(text = message, style = MaterialTheme.typography.bodyMedium)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = {
                runAction(if (running) "Applied" else "Saved") {
                    engine.updateSettings(edited)
                    saved = edited
                }
            },
            enabled = !busy && errors.isEmpty() && edited != saved,
        ) { Text(if (running) "Apply" else "Save") }
        OutlinedButton(
            onClick = {
                runAction("Reset to defaults") {
                    engine.resetSettings()
                    val defaults = EngineSettings()
                    saved = defaults
                    draft = defaults
                    trackersText = defaults.trackers.joinToString("\n")
                }
            },
            enabled = !busy,
        ) { Text("Reset to defaults") }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: Int,
    hint: String,
    onValueChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()

    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter(Char::isDigit).take(MAX_NUMBER_DIGITS)
            text.toIntOrNull()?.let(onValueChange)
        },
        label = { Text(label) },
        supportingText = { Text(if (parsed == null) "Enter a number." else hint) },
        isError = parsed == null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            summary?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun SubsectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

private fun EngineState.describe(version: String): String =
    when (this) {
        EngineState.NotInstalled -> "Not installed. Download the engine (about 5 MB) to play torrent links."
        is EngineState.Installing -> "Downloading engine $version..."
        EngineState.Stopped -> "Installed ($version). It starts when you play a torrent link."
        is EngineState.Running -> "Running ($version)"
        is EngineState.Failed -> "Error: $message"
    }

private val RETRACKER_MODES = listOf(
    "Use only the torrent's own trackers",
    "Add the extra trackers below (recommended)",
    "Remove all trackers (DHT and peer exchange only)",
    "Replace the torrent's trackers with the ones below",
)

private const val STATUS_REFRESH_MILLIS = 1_000L
private const val MAX_NUMBER_DIGITS = 6
