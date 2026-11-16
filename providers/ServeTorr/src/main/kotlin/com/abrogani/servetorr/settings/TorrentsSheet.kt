package com.abrogani.servetorr.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abrogani.servetorr.engine.TorrentEngine
import com.abrogani.servetorr.engine.TorrentInfo
import com.abrogani.servetorr.settings.icons.ServeTorrIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TorrentsSheet(
    engine: TorrentEngine,
    running: Boolean,
    backgroundScope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var torrents by remember { mutableStateOf<List<TorrentInfo>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var pendingRemoval by remember { mutableStateOf<TorrentInfo?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(running, refreshKey) {
        while (true) {
            refreshing = true
            try {
                torrents = engine.torrents()
                loadError = null
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                loadError = e.message ?: e.javaClass.simpleName
            } finally {
                refreshing = false
            }
            if (!running) break
            delay(TORRENTS_REFRESH_MILLIS)
        }
    }

    fun act(action: suspend () -> String) {
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
                refreshKey++
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainerLow,
        contentColor = colors.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SheetHeader(
                torrents = torrents,
                refreshing = refreshing,
                onRefresh = { refreshKey++ },
            )

            loadError?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium, color = colors.error)
            }
            if (message.isNotEmpty()) {
                Text(text = message, style = MaterialTheme.typography.bodyMedium, color = colors.primary)
            }
        }

        val list = torrents
        when {
            list == null -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp) }

            list.isEmpty() -> EmptyState(running)

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.hash }) { info ->
                    TorrentRow(info = info, enabled = !busy, onRemove = { pendingRemoval = info })
                }
            }
        }

        ClearCacheFooter(
            enabled = !busy,
            onClear = { confirmClear = true },
        )
    }

    pendingRemoval?.let { info ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            icon = { Icon(ServeTorrIcons.Delete, contentDescription = null, tint = colors.error) },
            title = { Text("Remove torrent?") },
            text = {
                Text("Stops \"${info.title}\" and deletes its cached data. It downloads again if you play it later.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemoval = null
                        act {
                            engine.removeTorrent(info.hash)
                            "Removed ${info.title}."
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") } },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(ServeTorrIcons.DeleteSweep, contentDescription = null, tint = colors.error) },
            title = { Text("Clear cache?") },
            text = {
                Text(
                    "Removes every torrent and its cache, including old engine leftovers. Engine and settings stay.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        act {
                            val result = engine.clearCache()
                            describeClear(result.torrentsRemoved, result.bytesFreed)
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
                ) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SheetHeader(
    torrents: List<TorrentInfo>?,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = "Torrents", style = MaterialTheme.typography.titleLarge)
            torrents?.let {
                Text(
                    text = summarize(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (refreshing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(ServeTorrIcons.Refresh, contentDescription = "Refresh", tint = colors.primary)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(running: Boolean) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(colors.primaryContainer.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(ServeTorrIcons.SwapVert, contentDescription = null, tint = colors.onPrimaryContainer)
        }
        Text(text = "No torrents", style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (running) "Played torrents show up here." else "Engine stopped. Nothing cached.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TorrentRow(
    info: TorrentInfo,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (info.active) {
            colors.primary.copy(alpha = 0.06f).compositeOver(colors.surfaceContainerHigh)
        } else {
            colors.surfaceContainer
        },
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = info.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusChip(label = info.status, active = info.active)
                    info.sizeBytes?.let { Meta(text = formatBytes(it)) }
                    peersLabel(info.peers, info.seeders)?.let { Meta(text = it, icon = ServeTorrIcons.Group) }
                    if (info.active) {
                        info.downloadBytesPerSecond?.let { Meta(text = formatSpeed(it), icon = ServeTorrIcons.ArrowDownward) }
                        info.uploadBytesPerSecond?.let { Meta(text = formatSpeed(it), icon = ServeTorrIcons.ArrowUpward) }
                    }
                    info.cachedBytes?.takeIf { it > 0 }?.let { Meta(text = "${formatBytes(it)} cached", icon = ServeTorrIcons.Storage) }
                }
            }
            IconButton(
                onClick = onRemove,
                enabled = enabled,
                colors = IconButtonDefaults.iconButtonColors(contentColor = colors.onSurfaceVariant),
            ) {
                Icon(ServeTorrIcons.Delete, contentDescription = "Remove ${info.title}")
            }
        }
    }
}

@Composable
private fun StatusChip(
    label: String,
    active: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val tint = if (active) colors.primary else colors.secondary
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (active) tint else tint.copy(alpha = 0.5f)),
        )
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

@Composable
private fun Meta(
    text: String,
    icon: ImageVector? = null,
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(14.dp)) }
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun ClearCacheFooter(
    enabled: Boolean,
    onClear: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column {
        HorizontalDivider(color = colors.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Engine and settings stay.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = onClear,
                enabled = enabled,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
                border = BorderStroke(1.dp, if (enabled) colors.error.copy(alpha = 0.6f) else colors.outlineVariant),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) { ButtonLabel(ServeTorrIcons.DeleteSweep, "Clear cache") }
        }
    }
}

private fun summarize(torrents: List<TorrentInfo>): String {
    val count = if (torrents.size == 1) "1 torrent" else "${torrents.size} torrents"
    val cached = torrents.mapNotNull { it.cachedBytes }.sum()
    val active = torrents.count { it.active }
    return buildString {
        append(count)
        if (active > 0 && active < torrents.size) append(", $active active")
        if (cached > 0) append(", ${formatBytes(cached)} on disk")
    }
}

private fun peersLabel(
    peers: Int?,
    seeders: Int?,
): String? =
    when {
        peers != null && seeders != null -> "$peers peers, $seeders seeders"
        peers != null -> if (peers == 1) "1 peer" else "$peers peers"
        seeders != null -> if (seeders == 1) "1 seeder" else "$seeders seeders"
        else -> null
    }

internal fun describeClear(
    torrentsRemoved: Int,
    bytesFreed: Long,
): String =
    if (torrentsRemoved == 0 && bytesFreed <= 0) {
        "Nothing to clear."
    } else {
        val count = if (torrentsRemoved == 1) "1 torrent" else "$torrentsRemoved torrents"
        "Removed $count, freed ${formatBytes(bytesFreed)}."
    }

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes.coerceAtLeast(0)} B"
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < BYTE_UNITS.lastIndex) {
        value /= 1024
        unit++
    }
    val pattern = if (value < 10) "%.1f %s" else "%.0f %s"
    return String.format(Locale.US, pattern, value, BYTE_UNITS[unit])
}

internal fun formatSpeed(bytesPerSecond: Long): String = "${formatBytes(bytesPerSecond)}/s"

private val BYTE_UNITS = listOf("B", "KB", "MB", "GB", "TB")
private const val TORRENTS_REFRESH_MILLIS = 2_000L
