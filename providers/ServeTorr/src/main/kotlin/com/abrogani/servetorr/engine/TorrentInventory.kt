package com.abrogani.servetorr.engine

import com.abrogani.servetorr.engine.TorrServerClient.TorrentStatus
import java.io.File

internal data class TorrentInfo(
    val hash: String,
    val title: String,
    val sizeBytes: Long?,
    val status: String,
    val active: Boolean,
    val peers: Int?,
    val seeders: Int?,
    val downloadBytesPerSecond: Long?,
    val uploadBytesPerSecond: Long?,
    val cachedBytes: Long?,
)

internal data class CacheClearResult(val torrentsRemoved: Int, val bytesFreed: Long)

internal class EngineLayout(filesDir: File, cacheDir: File, val version: String) {
    val filesRoot = File(filesDir, "torrent")
    val cacheRoot = File(cacheDir, "torrent")
    val engineRoot = File(filesRoot, "engine")
    val engineDir = File(engineRoot, version)
    val libraryFile = File(engineDir, EngineSource.LIBRARY_NAME)
    val dexFile = File(engineDir, EngineSource.DEX_NAME)
    val dataDir = File(cacheRoot, "server")
    val diskCacheDir = File(cacheRoot, "cache")
}

internal object TorrentInventory {
    const val STAT_ADDED = 0
    const val STAT_GETTING_INFO = 1
    const val STAT_PRELOAD = 2
    const val STAT_WORKING = 3
    const val STAT_CLOSED = 4
    const val STAT_IN_DB = 5

    const val DISK_ONLY_STATUS = "Cached on disk"

    val ENGINE_DATA_FILES = setOf("config.db", "settings.json", "viewed.json")

    private const val DEX_OPTIMIZED_DIR = "oat"

    private val INFO_HASH = Regex("[0-9a-fA-F]{40}")

    fun normalizeHash(hash: String?): String? =
        hash?.trim()?.takeIf { INFO_HASH.matches(it) }?.lowercase()

    fun isActive(stat: Int?): Boolean = stat == null || stat in STAT_ADDED..STAT_WORKING

    fun statusLabel(stat: Int?, downloadSpeed: Double?, uploadSpeed: Double?): String =
        when (stat) {
            STAT_ADDED -> "Starting"
            STAT_GETTING_INFO -> "Getting info"
            STAT_PRELOAD -> "Preloading"
            STAT_WORKING ->
                if ((downloadSpeed ?: 0.0) > 0.0 || (uploadSpeed ?: 0.0) > 0.0) "Streaming" else "Idle"
            STAT_CLOSED -> "Closed"
            STAT_IN_DB -> "Saved"
            else -> "Unknown"
        }

    fun toInfo(status: TorrentStatus, cachedBytes: Long?): TorrentInfo? {
        val hash = normalizeHash(status.hash) ?: return null
        val active = isActive(status.stat)
        return TorrentInfo(
            hash = hash,
            title = status.title?.takeIf { it.isNotBlank() } ?: status.name?.takeIf { it.isNotBlank() } ?: hash,
            sizeBytes = status.torrentSize?.takeIf { it > 0 },
            status = statusLabel(status.stat, status.downloadSpeed, status.uploadSpeed),
            active = active,
            peers = if (active) status.activePeers ?: 0 else null,
            seeders = if (active) status.connectedSeeders ?: 0 else null,
            downloadBytesPerSecond = if (active) (status.downloadSpeed ?: 0.0).toLong() else null,
            uploadBytesPerSecond = if (active) (status.uploadSpeed ?: 0.0).toLong() else null,
            cachedBytes = cachedBytes,
        )
    }

    fun merge(statuses: List<TorrentStatus>, diskCache: Map<String, Long>): List<TorrentInfo> {
        val fromEngine = statuses
            .mapNotNull { toInfo(it, diskCache[normalizeHash(it.hash)]) }
            .distinctBy { it.hash }
        val known = fromEngine.mapTo(HashSet()) { it.hash }
        val diskOnly = diskCache
            .filterKeys { it !in known }
            .entries
            .sortedByDescending { it.value }
            .map { (hash, bytes) ->
                TorrentInfo(
                    hash = hash,
                    title = hash,
                    sizeBytes = null,
                    status = DISK_ONLY_STATUS,
                    active = false,
                    peers = null,
                    seeders = null,
                    downloadBytesPerSecond = null,
                    uploadBytesPerSecond = null,
                    cachedBytes = bytes,
                )
            }
        return fromEngine.sortedByDescending { it.active } + diskOnly
    }

    fun diskCache(dir: File): Map<String, Long> {
        val sizes = linkedMapOf<String, Long>()
        dir.listFiles()?.forEach { child ->
            if (!child.isDirectory) return@forEach
            val hash = normalizeHash(child.name) ?: return@forEach
            sizes[hash] = (sizes[hash] ?: 0L) + sizeOf(child)
        }
        return sizes
    }

    fun cacheDirsOf(dir: File, hash: String): List<File> =
        dir.listFiles()?.filter { it.isDirectory && normalizeHash(it.name) == hash }.orEmpty()

    fun sizeOf(file: File): Long =
        if (!file.exists()) 0L else file.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun staleFiles(layout: EngineLayout, engineHoldsData: Boolean): List<File> {
        val engineFiles = setOf(layout.libraryFile.name, layout.dexFile.name, DEX_OPTIMIZED_DIR)
        return buildList {
            addAll(layout.filesRoot.children { it.name != layout.engineRoot.name })
            addAll(layout.engineRoot.children { it.name != layout.engineDir.name })
            addAll(layout.engineDir.children { it.name !in engineFiles })
            addAll(layout.cacheRoot.children { it.name != layout.dataDir.name && it.name != layout.diskCacheDir.name })
            addAll(layout.diskCacheDir.children { true })
            addAll(layout.dataDir.children { !engineHoldsData || it.name !in ENGINE_DATA_FILES })
        }
    }

    fun describeEngineFiles(layout: EngineLayout): String {
        val expected = listOf(layout.libraryFile, layout.dexFile).joinToString("; ") { file ->
            if (file.isFile) "${file.name} present (${file.length()} bytes) at ${file.absolutePath}"
            else "${file.name} missing at ${file.absolutePath}"
        }
        val found = if (layout.engineRoot.exists()) {
            layout.engineRoot.walkTopDown()
                .filter { it.isFile }
                .joinToString(", ") { "${it.relativeTo(layout.engineRoot).path} (${it.length()} bytes)" }
                .ifEmpty { "nothing" }
        } else {
            "no engine dir"
        }
        return "$expected; ${layout.engineRoot.absolutePath} has: $found"
    }

    private fun File.children(keep: (File) -> Boolean): List<File> =
        listFiles()?.filter(keep).orEmpty()
}
