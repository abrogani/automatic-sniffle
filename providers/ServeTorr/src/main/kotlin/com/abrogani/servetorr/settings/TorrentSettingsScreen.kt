package com.abrogani.servetorr.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.abrogani.servetorr.engine.EngineSettings
import com.abrogani.servetorr.engine.EngineState
import com.abrogani.servetorr.engine.TorrentEngine
import com.abrogani.servetorr.settings.theme.ServeTorrTheme
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
    ServeTorrTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            SettingsContent(
                settings = settings,
                engine = engine,
                isInterceptorInstalled = isInterceptorInstalled,
                proxyPort = proxyPort,
                onPortChange = onPortChange,
                backgroundScope = backgroundScope,
            )
        }
    }
}

@Composable
private fun SettingsContent(
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

    RestartPrompt(state)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        EngineCard(engine = engine, state = state, backgroundScope = backgroundScope)

        NetworkCallout()

        LinksSection(
            settings = settings,
            intercept = intercept,
            interceptorInstalled = interceptorInstalled,
            runningProxyPort = runningProxyPort,
            onPortChange = onPortChange,
        )

        EngineSettingsSection(engine = engine, running = state is EngineState.Running)
    }
}

@Composable
private fun RestartPrompt(state: EngineState) {
    var reason by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state) {
        if (state is EngineState.RestartRequired && state !== RestartPromptMemory.lastShown) {
            RestartPromptMemory.lastShown = state
            reason = state.reason
        }
    }

    val shown = reason ?: return
    AlertDialog(
        onDismissRequest = { reason = null },
        confirmButton = { TextButton(onClick = { reason = null }) { Text("OK") } },
        title = { Text("Restart required") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Restart the app to finish setting up the torrent engine.")
                if (shown.isNotBlank()) {
                    Text(
                        text = shown,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        icon = { StatusDot(MaterialTheme.colorScheme.tertiary, size = 14) },
    )
}

private object RestartPromptMemory {
    var lastShown: EngineState? = null
}

private data class StatusStyle(
    val label: String,
    val color: Color,
)

@Composable
private fun EngineState.style(): StatusStyle {
    val colors = MaterialTheme.colorScheme
    return when (this) {
        EngineState.NotInstalled -> StatusStyle("Not installed", colors.outline)
        is EngineState.Installing -> StatusStyle("Downloading", colors.secondary)
        EngineState.Stopped -> StatusStyle("Stopped", colors.onSurfaceVariant)
        is EngineState.Running -> StatusStyle("Running", colors.primary)
        is EngineState.Failed -> StatusStyle("Error", colors.error)
        is EngineState.RestartRequired -> StatusStyle("Restart needed", colors.tertiary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EngineCard(
    engine: TorrentEngine,
    state: EngineState,
    backgroundScope: CoroutineScope,
) {
    val installed = when (state) {
        EngineState.NotInstalled, is EngineState.Installing -> false
        is EngineState.Failed, is EngineState.RestartRequired -> engine.hasEngineFiles
        EngineState.Stopped, is EngineState.Running -> true
    }
    val status = state.style()
    val colors = MaterialTheme.colorScheme

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

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = status.color.copy(alpha = 0.10f).compositeOver(colors.surfaceContainerHigh),
        contentColor = colors.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "ServeTorr engine",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Version ${engine.engineVersion}",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                StatusPill(status)
            }

            Text(
                text = state.describe(engine.engineVersion),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state is EngineState.Failed) colors.error else colors.onSurfaceVariant,
            )

            (state as? EngineState.Installing)?.let {
                if (it.total > 0) {
                    LinearProgressIndicator(
                        progress = { (it.downloaded.toFloat() / it.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.secondary,
                        trackColor = colors.secondaryContainer,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.secondary,
                        trackColor = colors.secondaryContainer,
                    )
                }
            }

            if (installed) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { run { engine.stop(); "Engine stopped. It starts again when you play a torrent link." } },
                        enabled = !busy && running,
                    ) { Text("Stop engine") }
                    FilledTonalButton(
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
                }
                HorizontalDivider(color = colors.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Frees about 26 MB. Download it again any time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { run { engine.remove(); "Engine removed." } },
                        enabled = !busy,
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
                    ) { Text("Remove engine") }
                }
            } else {
                Button(
                    onClick = { backgroundScope.launch { engine.install() } },
                    enabled = state !is EngineState.Installing,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Download engine") }
            }

            if (message.isNotEmpty()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(status: StatusStyle) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(status.color.copy(alpha = 0.16f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(status.color, size = 8)
        Text(
            text = status.label,
            style = MaterialTheme.typography.labelLarge,
            color = status.color,
        )
    }
}

@Composable
private fun StatusDot(
    color: Color,
    size: Int,
) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun NetworkCallout() {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.tertiary.copy(alpha = 0.08f).compositeOver(colors.surfaceContainerLow),
        contentColor = colors.onSurface,
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(colors.tertiary),
            )
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Local server",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.tertiary,
                )
                Text(
                    text = "The engine runs a small server that only this device can reach. It starts when you play a " +
                        "torrent link and keeps sharing with peers while it runs. Stop it here when you're done.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
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

    SectionCard(
        title = "Links",
        description = "How ServeTorr picks up torrent links from your other providers.",
    ) {
        SwitchRow(
            title = "Play torrent links from all providers",
            summary = if (interceptorInstalled) {
                "Torrent links from your other providers play through the engine."
            } else {
                "Unavailable: this version of the app isn't supported. Torrent links won't be changed."
            },
            checked = intercept,
            enabled = interceptorInstalled,
            summaryIsError = !interceptorInstalled,
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
    var advancedOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(engine) {
        val loaded = engine.loadSettings()
        saved = loaded
        draft = loaded
        trackersText = loaded.trackers.joinToString("\n")
    }

    SectionHeader(
        title = "Engine settings",
        description = if (running) {
            "Applying settings restarts every active torrent, so anything playing from a torrent is interrupted."
        } else {
            "Settings are applied when the engine next starts."
        },
    )

    val current = draft ?: run {
        Text(
            text = "Loading...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
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

    SectionCard(
        title = "Cache",
        description = "How much of each torrent is buffered, and where.",
    ) {
        NumberField("Cache size per torrent (MB)", current.cacheSizeMb, "32 to 2048. Kept in memory unless stored on disk.") {
            update(current.copy(cacheSizeMb = it))
        }
        NumberField("Read-ahead (%)", current.readAheadPercent, "Share of the cache kept ahead of playback, 5 to 100.") {
            update(current.copy(readAheadPercent = it))
        }
        NumberField("Preload (%)", current.preloadPercent, "Share of the cache filled before playback starts, 0 to 100.") {
            update(current.copy(preloadPercent = it))
        }
        NumberField("Stop idle torrents after (seconds)", current.disconnectTimeoutSeconds, "1 to 3600.") {
            update(current.copy(disconnectTimeoutSeconds = it))
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
    }

    SectionCard(
        title = "Speed",
        description = "Limit how much bandwidth torrents use.",
    ) {
        NumberField("Download limit (KiB/s)", current.downloadRateLimitKib, "0 = unlimited.") {
            update(current.copy(downloadRateLimitKib = it))
        }
        NumberField("Upload limit (KiB/s)", current.uploadRateLimitKib, "0 = unlimited.") {
            update(current.copy(uploadRateLimitKib = it))
        }
        SwitchRow("Disable uploading", "Saves data, but peers may share less with you.", current.disableUpload) {
            update(current.copy(disableUpload = it))
        }
    }

    SectionCard(
        title = "Trackers",
        description = "Trackers help find peers faster.",
    ) {
        Column(modifier = Modifier.selectableGroup()) {
            RETRACKER_MODES.forEachIndexed { mode, label ->
                val selected = current.retrackersMode == mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { update(current.copy(retrackersMode = mode)) },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
    }

    AdvancedCard(open = advancedOpen, onToggle = { advancedOpen = !advancedOpen }) {
        SubsectionTitle("Connections")
        NumberField("Connections per torrent", current.connectionsLimit, "1 to 200.") {
            update(current.copy(connectionsLimit = it))
        }
        NumberField("Peer port", current.peersListenPort, "0 = random, or 1024 to 65535.") {
            update(current.copy(peersListenPort = it))
        }
        SwitchRow("Require encryption", "Only connects to peers that encrypt, so fewer peers.", current.forceEncrypt) {
            update(current.copy(forceEncrypt = it))
        }

        SubsectionTitle("Network")
        SwitchRow("Enable IPv6", null, current.enableIpv6) { update(current.copy(enableIpv6 = it)) }
        SwitchRow("Disable TCP", null, current.disableTcp) { update(current.copy(disableTcp = it)) }
        SwitchRow("Disable uTP", null, current.disableUtp) { update(current.copy(disableUtp = it)) }
        SwitchRow("Disable UPnP port forwarding", null, current.disableUpnp) { update(current.copy(disableUpnp = it)) }
        SwitchRow("Disable DHT", "Magnet links without trackers need DHT to find peers.", current.disableDht) {
            update(current.copy(disableDht = it))
        }
        SwitchRow("Disable peer exchange (PEX)", null, current.disablePex) { update(current.copy(disablePex = it)) }
        SwitchRow(
            "Local peer discovery",
            "Announces your torrents to other devices on the same network to find peers there.",
            current.enableLpd,
        ) { update(current.copy(enableLpd = it)) }

        SubsectionTitle("Diagnostics")
        SwitchRow("Debug logging", "Very verbose; only useful when reporting a problem.", current.enableDebug) {
            update(current.copy(enableDebug = it))
        }
    }

    if (errors.isNotEmpty() || message.isNotEmpty()) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            errors.forEach {
                Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (message.isNotEmpty()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
            modifier = Modifier.weight(1f),
        ) { Text("Reset to defaults") }
        Button(
            onClick = {
                runAction(if (running) "Applied" else "Saved") {
                    engine.updateSettings(edited)
                    saved = edited
                }
            },
            enabled = !busy && errors.isEmpty() && edited != saved,
            modifier = Modifier.weight(1f),
        ) { Text(if (running) "Apply" else "Save") }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    description: String?,
) {
    Column(
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    description: String?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun AdvancedCard(
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val rotation by animateFloatAsState(targetValue = if (open) 180f else 0f, label = "chevron")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = if (open) "Collapse" else "Expand", onClick = onToggle)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Advanced",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Connections, network protocols and debug logging. The defaults suit most people.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Chevron(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(rotation),
                )
            }
            AnimatedVisibility(
                visible = open,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun Chevron(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.28f, h * 0.40f)
            lineTo(w * 0.5f, h * 0.62f)
            lineTo(w * 0.72f, h * 0.40f)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
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
    summaryIsError: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.5f),
            )
            summary?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (summaryIsError) colors.error else colors.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

@Composable
private fun SubsectionTitle(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.secondary,
    )
}

private fun EngineState.describe(version: String): String =
    when (this) {
        EngineState.NotInstalled -> "Not installed. Download the engine (about 9 MB) to play torrent links."
        is EngineState.Installing -> "Downloading engine $version..."
        EngineState.Stopped -> "Installed ($version). It starts when you play a torrent link."
        is EngineState.Running -> "Running ($version)"
        is EngineState.Failed -> "Error: $message"
        is EngineState.RestartRequired -> "Restart the app to finish setting up the engine. $reason"
    }

private val RETRACKER_MODES = listOf(
    "Use only the torrent's own trackers",
    "Add the extra trackers below (recommended)",
    "Remove all trackers (DHT and peer exchange only)",
    "Replace the torrent's trackers with the ones below",
)

private const val STATUS_REFRESH_MILLIS = 1_000L
private const val MAX_NUMBER_DIGITS = 6
