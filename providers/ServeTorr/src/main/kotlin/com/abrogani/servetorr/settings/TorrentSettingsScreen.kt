package com.abrogani.servetorr.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.abrogani.servetorr.engine.EngineSettings
import com.abrogani.servetorr.engine.EngineState
import com.abrogani.servetorr.engine.TorrentEngine
import com.abrogani.servetorr.intercept.StreamsToggle
import com.abrogani.servetorr.settings.icons.ServeTorrIcons
import com.abrogani.servetorr.settings.theme.ServeTorrTheme
import com.flixclusive.provider.extensions.getBoolAsFlow
import com.flixclusive.provider.extensions.getInt
import com.flixclusive.provider.extensions.setBool
import com.flixclusive.provider.extensions.setInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Composable
internal fun TorrentSettingsScreen(
    settings: DataStore<Preferences>,
    engine: TorrentEngine,
    isInterceptorInstalled: () -> Boolean,
    streamsToggle: StateFlow<StreamsToggle?>,
    proxyPort: () -> Int?,
    onPortChange: (Int) -> Unit,
    backgroundScope: CoroutineScope,
) {
    ServeTorrTheme {
        SettingsContent(
            settings = settings,
            engine = engine,
            isInterceptorInstalled = isInterceptorInstalled,
            streamsToggle = streamsToggle,
            proxyPort = proxyPort,
            onPortChange = onPortChange,
            backgroundScope = backgroundScope,
        )
    }
}

@Composable
private fun SettingsContent(
    settings: DataStore<Preferences>,
    engine: TorrentEngine,
    isInterceptorInstalled: () -> Boolean,
    streamsToggle: StateFlow<StreamsToggle?>,
    proxyPort: () -> Int?,
    onPortChange: (Int) -> Unit,
    backgroundScope: CoroutineScope,
) {
    val state by engine.state.collectAsState()

    var interceptorInstalled by remember { mutableStateOf(isInterceptorInstalled()) }
    var runningProxyPort by remember { mutableStateOf(proxyPort()) }
    val toggle by streamsToggle.collectAsState()
    LaunchedEffect(Unit) {
        while (true) {
            interceptorInstalled = isInterceptorInstalled()
            runningProxyPort = proxyPort()
            delay(STATUS_REFRESH_MILLIS)
        }
    }

    val running = state is EngineState.Running
    val editor = remember(engine) { EngineSettingsEditor() }
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmApply by remember { mutableStateOf(false) }

    LaunchedEffect(engine) { editor.load(engine) }

    fun save() {
        scope.launch {
            val result = editor.save(engine, running)
            snackbars.currentSnackbarData?.dismiss()
            snackbars.showSnackbar(result)
        }
    }

    RestartPrompt(state)

    if (confirmApply) {
        AlertDialog(
            onDismissRequest = { confirmApply = false },
            title = { Text("Apply now?") },
            text = { Text("The engine is running. Applying restarts every active torrent.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmApply = false
                    save()
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { confirmApply = false }) { Text("Cancel") } },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbars) },
        floatingActionButton = {
            AnimatedVisibility(
                visible = editor.dirty,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(),
            ) {
                val valid = editor.errors.isEmpty() && !editor.busy
                ExtendedFloatingActionButton(
                    onClick = {
                        when {
                            editor.busy -> Unit
                            editor.errors.isNotEmpty() -> scope.launch {
                                snackbars.currentSnackbarData?.dismiss()
                                snackbars.showSnackbar(editor.errors.first())
                            }
                            running -> confirmApply = true
                            else -> save()
                        }
                    },
                    icon = { Icon(ServeTorrIcons.Check, contentDescription = null) },
                    text = { Text(if (running) "Apply" else "Save") },
                    containerColor = if (valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (valid) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) { _ ->
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
                interceptorInstalled = interceptorInstalled,
                streamsToggle = toggle,
                runningProxyPort = runningProxyPort,
                onPortChange = onPortChange,
            )

            UsageSection(settings = settings)

            EngineSettingsSection(engine = engine, running = running, editor = editor, snackbars = snackbars)

            ReferencesSection()

            Spacer(modifier = Modifier.height(FAB_CLEARANCE))
        }
    }
}

@Stable
private class EngineSettingsEditor {
    var draft by mutableStateOf<EngineSettings?>(null)
    var saved by mutableStateOf<EngineSettings?>(null)
    var trackersText by mutableStateOf("")
    var busy by mutableStateOf(false)

    val edited: EngineSettings?
        get() = draft?.copy(trackers = trackersText.lines().map(String::trim).filter(String::isNotEmpty))

    val errors: List<String>
        get() = edited?.errors().orEmpty()

    val dirty: Boolean
        get() = edited.let { it != null && it != saved }

    suspend fun load(engine: TorrentEngine) {
        show(engine.loadSettings())
    }

    fun show(settings: EngineSettings) {
        saved = settings
        draft = settings
        trackersText = settings.trackers.joinToString("\n")
    }

    suspend fun save(engine: TorrentEngine, running: Boolean): String {
        val target = edited ?: return "Nothing to save."
        busy = true
        return try {
            engine.updateSettings(target)
            saved = target
            if (running) "Applied." else "Saved."
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            e.message ?: e.javaClass.simpleName
        } finally {
            busy = false
        }
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
                Text("Restart the app to finish setting up the engine.")
                if (shown.isNotBlank()) {
                    Text(
                        text = shown,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        icon = { Icon(ServeTorrIcons.RestartAlt, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary) },
    )
}

private object RestartPromptMemory {
    var lastShown: EngineState? = null
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
    val colors = MaterialTheme.colorScheme
    val running = state is EngineState.Running
    val updatePending by engine.updatePending.collectAsState()
    val showPending = updatePending && state !is EngineState.Installing && state !is EngineState.RestartRequired

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var torrentsOpen by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

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

    if (torrentsOpen && installed) {
        TorrentsSheet(
            engine = engine,
            running = running,
            backgroundScope = backgroundScope,
            onDismiss = { torrentsOpen = false },
        )
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove engine?") },
            text = { Text("You can download it again any time.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = false
                        run { engine.remove(); "Engine removed." }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.surfaceContainerHigh,
        contentColor = colors.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ServeTorrLogo(size = 52.dp)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "ServeTorr",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "v${engine.engineVersion}",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant,
                        )
                        if (running) RunningDot()
                    }
                }
                if (installed) {
                    EngineToggle(
                        running = running,
                        enabled = !busy && state !is EngineState.RestartRequired,
                        onToggle = {
                            if (running) {
                                run { engine.stop(); "" }
                            } else {
                                run {
                                    engine.ensureRunning() ?: throw IllegalStateException("The engine isn't installed.")
                                    ""
                                }
                            }
                        },
                    )
                }
            }

            (if (showPending) UPDATE_PENDING_NOTICE else state.notice())?.let { notice ->
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        showPending -> colors.onSurfaceVariant
                        state is EngineState.Failed -> colors.error
                        state is EngineState.RestartRequired -> colors.tertiary
                        else -> colors.onSurfaceVariant
                    },
                )
            }

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
                HorizontalDivider(color = colors.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = { torrentsOpen = true },
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) { ButtonLabel(ServeTorrIcons.ViewList, "Torrents") }
                    OutlinedButton(
                        onClick = {
                            run {
                                when (val dropped = engine.dropAll()) {
                                    0 -> "Nothing was playing."
                                    1 -> "Stopped 1 torrent."
                                    else -> "Stopped $dropped torrents."
                                }
                            }
                        },
                        enabled = !busy && running,
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) { ButtonLabel(ServeTorrIcons.StopCircle, "Stop all") }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { confirmRemove = true }, enabled = !busy) {
                        Icon(ServeTorrIcons.Delete, contentDescription = "Remove engine", tint = colors.error)
                    }
                }
            } else {
                Button(
                    onClick = { backgroundScope.launch { engine.install() } },
                    enabled = state !is EngineState.Installing,
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    modifier = Modifier.fillMaxWidth(),
                ) { ButtonLabel(ServeTorrIcons.Download, "Download engine") }
            }

            if (message.isNotEmpty()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EngineToggle(
    running: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    FilledIconButton(
        onClick = onToggle,
        enabled = enabled,
        modifier = Modifier.size(52.dp),
        colors = if (running) {
            IconButtonDefaults.filledIconButtonColors(containerColor = colors.errorContainer, contentColor = colors.onErrorContainer)
        } else {
            IconButtonDefaults.filledIconButtonColors(containerColor = colors.primary, contentColor = colors.onPrimary)
        },
    ) {
        Icon(
            imageVector = if (running) ServeTorrIcons.Stop else ServeTorrIcons.PlayArrow,
            contentDescription = if (running) "Stop engine" else "Start engine",
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
private fun RunningDot() {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .semantics { contentDescription = "Running" },
    )
}

@Composable
private fun ServeTorrLogo(size: Dp) {
    val colors = MaterialTheme.colorScheme
    var loaded by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(percent = 22))
            .background(colors.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            Icon(
                imageVector = ServeTorrIcons.SwapVert,
                contentDescription = null,
                tint = colors.onPrimaryContainer.copy(alpha = 0.6f),
                modifier = Modifier.size(size / 2),
            )
        }
        AsyncImage(
            model = ICON_URL,
            contentDescription = "ServeTorr",
            contentScale = ContentScale.Crop,
            onState = { loaded = it is AsyncImagePainter.State.Success },
            modifier = Modifier.size(size),
        )
    }
}

@Composable
private fun NetworkCallout() {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.secondary.copy(alpha = 0.08f).compositeOver(colors.surfaceContainerLow),
        contentColor = colors.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = ServeTorrIcons.Shield,
                contentDescription = null,
                tint = colors.secondary,
                modifier = Modifier.size(20.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Local server",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.secondary,
                )
                Text(
                    text = "Only this device can reach it. Starts on play, stops when idle.",
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
    interceptorInstalled: Boolean,
    streamsToggle: StreamsToggle?,
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
        icon = ServeTorrIcons.Link,
        description = null,
    ) {
        when {
            !interceptorInstalled -> "Torrent links can't be handled on this app version."
            streamsToggle == StreamsToggle.OFF -> "Off. Turn on Streams for ServeTorr in Providers."
            streamsToggle == StreamsToggle.UNREADABLE -> "Can't read the Streams toggle; torrent links are always handled."
            else -> null
        }?.let {
            Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

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
                        !portValid -> "1024 to 65535."
                        runningProxyPort == null -> "Port $port is in use."
                        runningProxyPort != port -> "Switching to $port..."
                        else -> "Changing it breaks links found earlier."
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
private fun UsageSection(settings: DataStore<Preferences>) {
    val scope = rememberCoroutineScope()
    val wifiOnly by remember(settings) {
        settings.getBoolAsFlow(WIFI_ONLY_KEY, DEFAULT_WIFI_ONLY)
    }.collectAsState(initial = DEFAULT_WIFI_ONLY)
    var idleText by remember { mutableStateOf("") }

    LaunchedEffect(settings) {
        idleText = settings.getInt(IDLE_STOP_MINUTES_KEY, DEFAULT_IDLE_STOP_MINUTES).toString()
    }

    SectionCard(
        title = "Data and battery",
        icon = ServeTorrIcons.Power,
        description = null,
    ) {
        SwitchRow(
            title = "Wi-Fi only",
            summary = "Never runs on mobile data.",
            checked = wifiOnly,
            icon = ServeTorrIcons.Wifi,
            onCheckedChange = { scope.launch { settings.setBool(WIFI_ONLY_KEY, it) } },
        )

        val idleValid = idleText.toIntOrNull()?.let { it in IDLE_STOP_RANGE } == true
        OutlinedTextField(
            value = idleText,
            onValueChange = { input ->
                idleText = input.filter(Char::isDigit).take(3)
                idleText.toIntOrNull()
                    ?.takeIf { it in IDLE_STOP_RANGE }
                    ?.let { minutes -> scope.launch { settings.setInt(IDLE_STOP_MINUTES_KEY, minutes) } }
            },
            label = { Text("Stop when idle (minutes)") },
            leadingIcon = { Icon(ServeTorrIcons.Timer, contentDescription = null) },
            supportingText = {
                Text(
                    if (idleValid) {
                        "0 = never."
                    } else {
                        "0 to ${IDLE_STOP_RANGE.last}."
                    },
                )
            },
            isError = !idleValid,
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
    editor: EngineSettingsEditor,
    snackbars: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    var advancedOpen by rememberSaveable { mutableStateOf(false) }

    SectionHeader(
        title = "Engine settings",
        icon = ServeTorrIcons.Settings,
        description = if (running) "Applying interrupts playback." else null,
    )

    val current = editor.draft ?: run {
        Text(
            text = "Loading...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        return
    }
    val update: (EngineSettings) -> Unit = { editor.draft = it }

    SectionCard(
        title = "Cache",
        icon = ServeTorrIcons.Storage,
        description = null,
    ) {
        NumberField("Cache per torrent (MB)", current.cacheSizeMb, "32 to 2048.") {
            update(current.copy(cacheSizeMb = it))
        }
        NumberField("Read-ahead (%)", current.readAheadPercent, "5 to 100.") {
            update(current.copy(readAheadPercent = it))
        }
        NumberField("Preload (%)", current.preloadPercent, "0 to 100.") {
            update(current.copy(preloadPercent = it))
        }
        NumberField("Drop idle torrents after (s)", current.disconnectTimeoutSeconds, "1 to 3600.") {
            update(current.copy(disconnectTimeoutSeconds = it))
        }
        SwitchRow(
            title = "Store cache on disk",
            summary = "Instead of memory.",
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
        icon = ServeTorrIcons.Speed,
        description = null,
    ) {
        NumberField("Download limit (KiB/s)", current.downloadRateLimitKib, "0 = unlimited.") {
            update(current.copy(downloadRateLimitKib = it))
        }
        NumberField("Upload limit (KiB/s)", current.uploadRateLimitKib, "0 = unlimited.") {
            update(current.copy(uploadRateLimitKib = it))
        }
        SwitchRow("Disable uploading", null, current.disableUpload) {
            update(current.copy(disableUpload = it))
        }
    }

    SectionCard(
        title = "Trackers",
        icon = ServeTorrIcons.SwapVert,
        description = null,
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
            value = editor.trackersText,
            onValueChange = { editor.trackersText = it },
            label = { Text("Extra trackers") },
            supportingText = { Text("One URL per line.") },
            textStyle = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
            ),
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
    }

    AdvancedCard(open = advancedOpen, onToggle = { advancedOpen = !advancedOpen }) {
        SubsectionTitle("Connections")
        NumberField("Connections per torrent", current.connectionsLimit, "1 to 200.") {
            update(current.copy(connectionsLimit = it))
        }
        NumberField("Peer port", current.peersListenPort, "0 = random.") {
            update(current.copy(peersListenPort = it))
        }
        SwitchRow("Require encryption", "Fewer peers.", current.forceEncrypt) {
            update(current.copy(forceEncrypt = it))
        }

        SubsectionTitle("Network")
        SwitchRow("Enable IPv6", null, current.enableIpv6) { update(current.copy(enableIpv6 = it)) }
        SwitchRow("Disable TCP", null, current.disableTcp) { update(current.copy(disableTcp = it)) }
        SwitchRow("Disable uTP", null, current.disableUtp) { update(current.copy(disableUtp = it)) }
        SwitchRow("Disable UPnP port forwarding", null, current.disableUpnp) { update(current.copy(disableUpnp = it)) }
        SwitchRow("Disable DHT", null, current.disableDht) {
            update(current.copy(disableDht = it))
        }
        SwitchRow("Disable peer exchange (PEX)", null, current.disablePex) { update(current.copy(disablePex = it)) }
        SwitchRow(
            "Local peer discovery",
            "Find peers on your network.",
            current.enableLpd,
        ) { update(current.copy(enableLpd = it)) }

        SubsectionTitle("Diagnostics")
        SwitchRow("Debug logging", null, current.enableDebug) {
            update(current.copy(enableDebug = it))
        }
    }

    editor.errors.takeIf { it.isNotEmpty() }?.let { errors ->
        Column(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            errors.forEach {
                Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    OutlinedButton(
        onClick = {
            editor.busy = true
            scope.launch {
                val result = try {
                    engine.resetSettings()
                    editor.show(EngineSettings())
                    "Reset to defaults."
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    e.message ?: e.javaClass.simpleName
                } finally {
                    editor.busy = false
                }
                snackbars.currentSnackbarData?.dismiss()
                snackbars.showSnackbar(result)
            }
        },
        enabled = !editor.busy,
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        modifier = Modifier.fillMaxWidth(),
    ) { ButtonLabel(ServeTorrIcons.RestartAlt, "Reset to defaults") }
}

@Composable
private fun ReferencesSection() {
    SectionCard(
        title = "References",
        icon = ServeTorrIcons.Info,
        description = null,
    ) {
        REFERENCES.forEach { ReferenceRow(it) }
    }
}

private data class Reference(val title: String, val owner: String, val repo: String) {
    val url get() = "https://github.com/$owner/$repo"
    val display get() = "github.com/$owner/$repo"
    val avatar get() = "https://github.com/$owner.png"
}

private val REFERENCES = listOf(
    Reference("TorrServer", "YouROK", "TorrServer"),
    Reference("ServeTorr engine", "abrogani", "servetorr-engine"),
    Reference("TorrServe", "YouROK", "TorrServe"),
)

@Composable
private fun ReferenceRow(reference: Reference) {
    val uriHandler = LocalUriHandler.current
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClickLabel = "Open ${reference.display}") {
                runCatching { uriHandler.openUri(reference.url) }
            }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AsyncImage(
            model = reference.avatar,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(colors.surfaceVariant),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = reference.title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            Text(
                text = reference.display,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = ServeTorrIcons.OpenInNew,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    icon: ImageVector,
    description: String?,
) {
    Column(
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
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
    icon: ImageVector,
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
                SectionTitle(title = title, icon = icon)
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
                    SectionTitle(title = "Advanced", icon = ServeTorrIcons.Tune)
                    Text(
                        text = "Connections, protocols, logging.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = ServeTorrIcons.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun SectionTitle(
    title: String,
    icon: ImageVector,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
internal fun ButtonLabel(
    icon: ImageVector,
    text: String,
) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
    Text(text)
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
    icon: ImageVector? = null,
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
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = if (enabled) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
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

private fun EngineState.notice(): String? =
    when (this) {
        EngineState.NotInstalled -> "Download the engine to play torrent links."
        is EngineState.Installing -> "Downloading..."
        EngineState.Stopped, is EngineState.Running -> null
        is EngineState.Failed -> message
        is EngineState.RestartRequired -> "Restart the app. $reason"
    }

private const val UPDATE_PENDING_NOTICE = "Update pending — waiting for Wi-Fi."

private val RETRACKER_MODES = listOf(
    "Torrent's own only",
    "Add extra trackers (recommended)",
    "None (DHT and PEX only)",
    "Replace with extra trackers",
)

private const val STATUS_REFRESH_MILLIS = 1_000L
private val FAB_CLEARANCE = 88.dp
private const val MAX_NUMBER_DIGITS = 6
private val IDLE_STOP_RANGE = 0..240
