package com.abrogani.servetorr.engine

import android.content.Context
import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.abrogani.servetorr.engine.EngineRuntime.sha256
import com.abrogani.servetorr.engine.EngineSettingsCodec.STORE_KEY
import com.abrogani.servetorr.engine.TorrServerClient.TorrentMetadata
import com.abrogani.servetorr.engine.TorrServerClient.TorrentMetadataTimeout
import com.abrogani.servetorr.link.TorrentFile
import com.abrogani.servetorr.link.TorrentLink
import com.flixclusive.core.util.coroutines.FlxDispatchers.Companion.withIOContext
import com.flixclusive.core.util.log.errorLog
import com.flixclusive.core.util.log.infoLog
import com.flixclusive.core.util.log.warnLog
import com.flixclusive.provider.extensions.getBool
import com.flixclusive.provider.extensions.getString
import com.flixclusive.provider.extensions.setString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.UUID

internal class TorrentEngine(
    context: Context,
    client: OkHttpClient,
    private val store: () -> DataStore<Preferences>,
    private val idleStopMinutes: suspend () -> Int,
) : TorrentBackend {
    private val appContext = context.applicationContext ?: context
    private val installer = EngineInstaller(client)
    private val api = TorrServerClient(client)

    val state: StateFlow<EngineState> = EngineRuntime.state.asStateFlow()
    val engineVersion: String = EngineSource.VERSION
    val updatePending: StateFlow<Boolean> = EngineRuntime.updatePending.asStateFlow()

    private val _settings = MutableStateFlow(EngineSettings())

    val settings: StateFlow<EngineSettings> = _settings.asStateFlow()

    private val settingsLoadMutex = Mutex()

    @Volatile
    private var settingsLoaded = false

    private val layout get() = EngineLayout(appContext.filesDir, appContext.cacheDir, EngineSource.VERSION)
    private val rootDir get() = layout.engineRoot
    private val engineDir get() = layout.engineDir
    private val libraryFile get() = layout.libraryFile
    private val dexFile get() = layout.dexFile
    private val dataDir get() = layout.dataDir
    private val diskCacheDir get() = layout.diskCacheDir

    private val expectedPins: String? by lazy {
        EngineSource.selectAbi(EngineRuntime.processAbis())?.let(EngineSource::pinFingerprint)
    }

    init {
        when (EngineRuntime.state.value) {
            EngineState.NotInstalled, EngineState.Stopped, is EngineState.RestartRequired -> settle()
            is EngineState.Installing, is EngineState.Running, is EngineState.Failed -> Unit
        }
    }

    override suspend fun ensureRunning(): Int? {
        livePort()?.let { return it }

        val start = EngineRuntime.mutex.withLock {
            livePort()?.let { return it }
            EngineRuntime.pendingStart?.takeIf { it.isActive }
                ?: EngineRuntime.scope.async { startReportingFailure() }.also { EngineRuntime.pendingStart = it }
        }
        return start.await()
    }

    private fun livePort(): Int? {
        val port = EngineRuntime.port ?: return null
        if (EngineRuntime.flags.startedPort == port) return port
        EngineRuntime.port = null
        return null
    }

    private suspend fun startReportingFailure(): Int? =
        try {
            start()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e

            errorLog(e)
            val reason = when {
                e is EngineRestartRequired -> e.reason
                EngineRuntime.flags.startFailed -> EngineStartPolicy.START_FAILED_MESSAGE
                else -> null
            }
            if (reason != null) {
                EngineRuntime.state.value = EngineState.RestartRequired(reason)
                throw EngineRestartRequired(reason).apply { initCause(e) }
            }
            val message = e.message ?: e.javaClass.simpleName
            EngineRuntime.state.value = EngineState.Failed(message)
            throw IllegalStateException(message, e)
        }

    private suspend fun start(): Int? {
        val flags = EngineRuntime.flags
        val started = flags.startedPort
        val alive = started != null && api.waitForEcho(started, REUSE_ECHO_MILLIS)

        val decision = EngineStartPolicy.decide(flags, EngineSource.VERSION, alive, EngineRuntime.bindingsAvailable)
        when (decision) {
            is EngineStartPolicy.Decision.RestartRequired -> {
                if (!isInstalled()) return notInstalled()
                throw EngineRestartRequired(decision.reason)
            }
            is EngineStartPolicy.Decision.Reuse -> {
                infoLog("[ServeTorr] Reusing the engine on port ${decision.port}")
                applyPersistedSettings(decision.port)
                return running(decision.port)
            }
            EngineStartPolicy.Decision.Start -> Unit
        }

        if (!isInstalled()) return notInstalled()

        val result = withIOContext {
            dataDir.mkdirs()
            EngineRuntime.start(appContext, engineDir, dataDir)
        }
        val port = EngineStartPolicy.recordStart(flags, result)
            ?: throw EngineRestartRequired(EngineStartPolicy.START_FAILED_MESSAGE)

        if (!api.waitForEcho(port, START_TIMEOUT_MILLIS)) {
            throw IllegalStateException("The torrent engine started on port $port but isn't responding.")
        }

        infoLog("[ServeTorr] Engine running on port $port")
        applyPersistedSettings(port)
        return running(port)
    }

    private fun notInstalled(): Int? {
        if (EngineRuntime.state.value !is EngineState.Installing) {
            EngineRuntime.state.value = EngineRuntime.installFailure?.let(EngineState::Failed) ?: EngineState.NotInstalled
        }
        return null
    }

    private suspend fun applyPersistedSettings(port: Int) {
        try {
            loadSettings()
            withSettingsTimeout { applySettings(port) }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            warnLog("[ServeTorr] Could not apply the engine settings: ${e.message}")
        }
    }

    private fun running(port: Int): Int {
        EngineRuntime.port = port
        EngineRuntime.state.value = EngineState.Running(port)
        watchIdle(port)
        return port
    }

    private fun watchIdle(port: Int) {
        val token = UUID.randomUUID().toString()
        System.setProperty(IDLE_WATCHER_PROPERTY, token)
        EngineRuntime.idleWatcher?.cancel()
        EngineRuntime.idleWatcher = EngineRuntime.scope.launch {
            val timer = IdleStopTimer(SystemClock::elapsedRealtime)
            while (isActive) {
                delay(IDLE_POLL_MILLIS)
                if (!ownsWatcher(token, port)) break

                val minutes = try {
                    idleStopMinutes()
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    0
                }
                if (!timer.shouldStop(isBusy(port), minutes)) continue
                if (!ownsWatcher(token, port) || isBusy(port)) continue

                infoLog("[ServeTorr] No torrents for $minutes min; stopping the engine")
                try {
                    stop()
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    warnLog("[ServeTorr] Could not stop the idle engine: ${e.message}")
                    continue
                }
                break
            }
        }
    }

    private fun ownsWatcher(token: String, port: Int): Boolean =
        System.getProperty(IDLE_WATCHER_PROPERTY) == token &&
            EngineRuntime.port == port &&
            EngineRuntime.flags.startedPort == port

    private suspend fun isBusy(port: Int): Boolean {
        if (EngineRuntime.activeResolutions.get() > 0) return true
        if (EngineRuntime.pendingStart?.isActive == true) return true
        return try {
            api.list(port).any { TorrentInventory.isActive(it.stat) }
        } catch (e: IOException) {
            true
        }
    }

    private suspend fun <T> withEngine(block: suspend (port: Int) -> T): T {
        val port = ensureRunning() ?: throw IllegalStateException("The torrent engine isn't installed")
        return try {
            block(port)
        } catch (e: IOException) {
            if (e !is TorrentMetadataTimeout && !api.echo(port)) {
                EngineRuntime.mutex.withLock {
                    if (EngineRuntime.port == port) {
                        EngineRuntime.port = null
                        EngineRuntime.state.value = EngineState.RestartRequired(EngineStartPolicy.STOPPED_MESSAGE)
                    }
                }
            }
            throw e
        }
    }

    val hasEngineFiles: Boolean
        get() = libraryFile.isFile && dexFile.isFile && pinsState() != EngineUpdate.Pins.MISMATCH

    private fun pinsState(): EngineUpdate.Pins = EngineUpdate.pinsState(layout.pinsMarker, expectedPins)

    override suspend fun files(link: TorrentLink): List<TorrentFile> = metadata(link).files

    override suspend fun infoHash(link: TorrentLink): String? =
        link.infoHash?.takeIf { it.isNotBlank() }?.lowercase() ?: metadata(link).hash.takeIf { it.isNotBlank() }

    private suspend fun metadata(link: TorrentLink): TorrentMetadata {
        EngineRuntime.activeResolutions.incrementAndGet()
        try {
            return withEngine { port ->
                api.metadata(port, link.source, link.infoHash, METADATA_TIMEOUT_MILLIS, EngineRuntime.dropGeneration)
            }
        } finally {
            EngineRuntime.activeResolutions.decrementAndGet()
        }
    }

    suspend fun dropAll(): Int {
        val port = EngineRuntime.port
            ?: EngineRuntime.flags.startedPort?.takeIf { api.echo(it) }
            ?: return 0
        try {
            val hashes = api.list(port).mapNotNull { it.hash }
            hashes.forEach { api.drop(port, it) }
            return hashes.size
        } finally {
            EngineRuntime.dropGeneration.update { it + 1 }
        }
    }

    suspend fun torrents(): List<TorrentInfo> {
        val disk = withIOContext { TorrentInventory.diskCache(diskCacheDir) }
        val port = livePort() ?: return TorrentInventory.merge(emptyList(), disk)
        val statuses = try {
            api.list(port)
        } catch (e: IOException) {
            warnLog("[ServeTorr] Could not list the engine's torrents: ${e.message}")
            emptyList()
        }
        return TorrentInventory.merge(statuses, disk)
    }

    suspend fun removeTorrent(hash: String) {
        val key = requireNotNull(TorrentInventory.normalizeHash(hash)) { "Not a torrent info hash: $hash" }
        EngineRuntime.mutex.withLock {
            val port = livePort()
            if (port != null) {
                try {
                    api.remove(port, key)
                    try {
                        api.removeViewed(port, key)
                    } catch (e: IOException) {
                        warnLog("[ServeTorr] Could not clear the viewed state of $key: ${e.message}")
                    }
                } finally {
                    EngineRuntime.dropGeneration.update { it + 1 }
                }
            }
            val freed = withIOContext {
                TorrentInventory.cacheDirsOf(diskCacheDir, key).sumOf { dir ->
                    TorrentInventory.sizeOf(dir).also { dir.deleteTree() }
                }
            }
            infoLog("[ServeTorr] Removed torrent $key ($freed bytes of disk cache)")
        }
    }

    suspend fun clearCache(): CacheClearResult {
        val result = EngineRuntime.mutex.withLock {
            check(EngineRuntime.pendingStart?.isActive != true) { "The engine is still starting. Try again in a moment." }
            EngineRuntime.installLock.tryWithLock(layout.lockFile) { clearCacheLocked() }
                ?: throw IllegalStateException("The engine is being downloaded. Try again when it's done.")
        }
        settle()
        return result
    }

    private suspend fun clearCacheLocked(): CacheClearResult {
        val layout = layout
        val port = livePort()
        val holdsData = port != null || EngineRuntime.flags.startedPort != null
        val before = withIOContext { TorrentInventory.sizeOf(layout.filesRoot) + TorrentInventory.sizeOf(layout.cacheRoot) }
        val hashes = withIOContext { TorrentInventory.diskCache(layout.diskCacheDir).keys.toMutableSet() }

        if (updateNeeded()) withIOContext { writeWanted(true) }

        if (port != null) {
            try {
                api.list(port).mapNotNullTo(hashes) { TorrentInventory.normalizeHash(it.hash) }
                api.wipe(port)
                api.viewedHashes(port).forEach { api.removeViewed(port, it) }
            } finally {
                EngineRuntime.dropGeneration.update { it + 1 }
            }
        }

        withIOContext {
            TorrentInventory.staleFiles(layout, holdsData).forEach { file ->
                infoLog("[ServeTorr] Clearing ${file.absolutePath} (${TorrentInventory.sizeOf(file)} bytes)")
                file.deleteTree()
            }
        }
        if (port != null) applyPersistedSettings(port)

        val after = withIOContext { TorrentInventory.sizeOf(layout.filesRoot) + TorrentInventory.sizeOf(layout.cacheRoot) }
        val result = CacheClearResult(torrentsRemoved = hashes.size, bytesFreed = (before - after).coerceAtLeast(0L))
        infoLog("[ServeTorr] Cleared the cache: ${result.torrentsRemoved} torrents, ${result.bytesFreed} bytes")
        return result
    }

    suspend fun purge() {
        try {
            stop()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            warnLog("[ServeTorr] Could not stop the engine before removing it: ${e.message}")
        }
        EngineRuntime.installLock.withLock(layout.lockFile) {
            withIOContext {
                val layout = layout
                listOf(layout.filesRoot, layout.cacheRoot).forEach { dir ->
                    if (dir.exists()) infoLog("[ServeTorr] Deleting ${dir.absolutePath} (${TorrentInventory.sizeOf(dir)} bytes)")
                    dir.deleteTree()
                }
                EngineRuntime.verifiedEngine = null
            }
            EngineRuntime.installFailure = null
            EngineRuntime.updatePending.value = false
            settle()
        }
    }

    suspend fun stop() {
        EngineRuntime.mutex.withLock {
            check(EngineRuntime.pendingStart?.isActive != true) { "The engine is still starting. Try again in a moment." }
            val flags = EngineRuntime.flags
            if (EngineRuntime.port == null && flags.startedPort == null) return@withLock

            val stopped = withIOContext { EngineRuntime.stop() }
            check(stopped) { "This copy of the provider can't reach the engine. Restart the app to stop it." }

            EngineRuntime.port = null
            flags.startedPort = null
            EngineRuntime.dropGeneration.update { it + 1 }
            System.clearProperty(IDLE_WATCHER_PROPERTY)
            infoLog("[ServeTorr] Engine stopped")
        }
        settle()
    }

    suspend fun loadSettings(): EngineSettings {
        if (settingsLoaded) return _settings.value
        settingsLoadMutex.withLock {
            if (!settingsLoaded) {
                _settings.value = EngineSettingsCodec.decode(store().getString(STORE_KEY, null))
                settingsLoaded = true
            }
        }
        return _settings.value
    }

    suspend fun updateSettings(new: EngineSettings) {
        val errors = new.errors()
        require(errors.isEmpty()) { errors.joinToString("\n") }

        persist(new)
        val port = EngineRuntime.port ?: return
        withSettingsTimeout { applySettings(port) }
    }

    suspend fun resetSettings() {
        persist(null)
        val port = EngineRuntime.port ?: return
        withSettingsTimeout {
            EngineRuntime.settingsMutex.withLock {
                api.resetSettings(port)
                EngineRuntime.dropGeneration.update { it + 1 }
            }
            applySettings(port)
        }
    }

    private suspend fun persist(new: EngineSettings?) {
        settingsLoadMutex.withLock {
            store().setString(STORE_KEY, new?.let(EngineSettingsCodec::encode))
            _settings.value = new ?: EngineSettings()
            settingsLoaded = true
        }
    }

    private suspend fun applySettings(port: Int) {
        EngineRuntime.settingsMutex.withLock {
            val wanted = _settings.value
            val savePath = diskCacheDir.absolutePath
            val current = api.getSettings(port)

            if (EngineSettingsCodec.differs(current, wanted, savePath)) {
                if (wanted.useDisk) withIOContext { diskCacheDir.mkdirs() }
                api.setSettings(port, EngineSettingsCodec.merge(current, wanted, savePath))
                EngineRuntime.dropGeneration.update { it + 1 }
                infoLog("[ServeTorr] Applied engine settings")
            }

            val added = withIOContext { EngineRuntime.addTrackers(wanted.trackers) }
            if (!added) warnLog("[ServeTorr] Engine bindings unavailable; trackers not updated")
        }
    }

    private suspend fun withSettingsTimeout(block: suspend () -> Unit) {
        withTimeoutOrNull(SETTINGS_TIMEOUT_MILLIS) { block() }
            ?: throw IllegalStateException("The torrent engine didn't apply the settings in time.")
    }

    suspend fun install(automatic: Boolean = false, stillCurrent: suspend () -> Boolean = { true }) {
        EngineRuntime.installLock.withLock(layout.lockFile) {
            try {
                if (automatic) {
                    if (!stillCurrent()) {
                        infoLog("[ServeTorr] This copy of the provider is no longer loaded; skipping the engine update")
                        return@withLock
                    }
                    if (!updateNeeded()) {
                        infoLog("[ServeTorr] The engine update is no longer needed")
                        return@withLock
                    }
                }

                if (pinsState() != EngineUpdate.Pins.MISMATCH && isInstalled()) {
                    EngineRuntime.installFailure = null
                    settle()
                    if (!automatic) withIOContext { writeWanted(true) }
                    return@withLock
                }

                val abis = EngineRuntime.processAbis()
                val abi = EngineSource.selectAbi(abis)
                if (abi == null) {
                    fail("This device's CPU (${abis.joinToString()}) isn't supported by the torrent engine.")
                    return@withLock
                }

                EngineRuntime.state.value = EngineState.Installing(0, 0)
                withIOContext {
                    val layout = layout
                    layout.engineDir.mkdirs()
                    installer.install(abi, layout.engineDir) { downloaded, total ->
                        EngineRuntime.state.value = EngineState.Installing(downloaded, total)
                    }
                    EngineSource.pinFingerprint(abi)?.let { EngineUpdate.writePins(layout.pinsMarker, it) }
                    EngineRuntime.verifiedEngine = layout.engineDir.absolutePath
                    EngineUpdate.replacedByInstall(layout.engineRoot, EngineSource.VERSION).forEach {
                        infoLog("[ServeTorr] Deleting old engine files ${it.absolutePath} (${TorrentInventory.sizeOf(it)} bytes)")
                        it.deleteTree()
                    }
                    EngineUpdate.newerVersions(layout.engineRoot, EngineSource.VERSION).forEach {
                        infoLog("[ServeTorr] Keeping newer engine files ${File(layout.engineRoot, it).absolutePath}")
                    }
                }
                EngineRuntime.installFailure = null
                settle()
                if (hasEngineFiles) withIOContext { writeWanted(true) }
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    settle()
                    throw e
                }

                errorLog(e)
                fail(
                    if (e is EngineInstaller.EngineNotPublished) {
                        e.message ?: "This engine version isn't published yet."
                    } else {
                        "Couldn't install the torrent engine: ${e.message ?: e.javaClass.simpleName}"
                    }
                )
            }
        }
    }

    private fun fail(message: String) {
        EngineRuntime.installFailure = message
        EngineRuntime.state.value = EngineState.Failed(message)
    }

    suspend fun remove() {
        stop()
        EngineRuntime.installLock.withLock(layout.lockFile) {
            withIOContext {
                writeWanted(false)
                infoLog("[ServeTorr] Removing the engine: ${TorrentInventory.describeEngineFiles(layout)}")
                rootDir.deleteTree()
                dataDir.deleteTree()
                diskCacheDir.deleteTree()
                EngineRuntime.verifiedEngine = null
            }
            EngineRuntime.installFailure = null
            EngineRuntime.updatePending.value = false
            settle()
        }
    }

    suspend fun autoUpdate(
        blockReason: suspend () -> String?,
        wakeups: Flow<Any?>,
        stillCurrent: suspend () -> Boolean,
    ) = EngineAutoUpdate(
        updateNeeded = ::updateNeeded,
        install = { install(automatic = true, stillCurrent = stillCurrent) },
        blockReason = blockReason,
        wakeups = wakeups,
        pending = EngineRuntime.updatePending,
    ).run()

    suspend fun updateNeeded(): Boolean = withIOContext {
        val layout = layout
        val previous = EngineUpdate.previousVersions(layout.engineRoot, EngineSource.VERSION)
        val newer = EngineUpdate.newerVersions(layout.engineRoot, EngineSource.VERSION)
        val present = layout.libraryFile.isFile && layout.dexFile.isFile
        val stalePins = present && resolvePins(layout) == EngineUpdate.Pins.MISMATCH
        val update = EngineUpdate.shouldUpdate(
            installed = present && !stalePins,
            wanted = readWanted(),
            previousVersions = previous,
            stalePins = stalePins,
            newerInstalled = newer.isNotEmpty(),
        )
        if (update) {
            infoLog(
                "[ServeTorr] Engine ${EngineSource.VERSION} isn't installed or is outdated; updating " +
                    "(stale pins: $stalePins, previous: ${previous.ifEmpty { listOf("none on disk") }.joinToString()})"
            )
        } else if (newer.isNotEmpty() && !present) {
            infoLog("[ServeTorr] A newer engine (${newer.joinToString()}) is installed; not downloading ${EngineSource.VERSION}")
        }
        update
    }

    private fun resolvePins(layout: EngineLayout): EngineUpdate.Pins {
        val state = EngineUpdate.pinsState(layout.pinsMarker, expectedPins)
        if (state != EngineUpdate.Pins.UNKNOWN) return state
        val abi = EngineSource.selectAbi(EngineRuntime.processAbis()) ?: return state
        val files = EngineSource.engineFiles(abi) ?: return state
        val actual = files.map { it.fileName to File(layout.engineDir, it.fileName).sha256() }
        val fingerprint = EngineSource.fingerprint(EngineSource.VERSION, actual)
        runCatching { EngineUpdate.writePins(layout.pinsMarker, fingerprint) }
            .onFailure { warnLog("[ServeTorr] Could not record the engine pins: ${it.message}") }
        return if (fingerprint == expectedPins) EngineUpdate.Pins.MATCH else EngineUpdate.Pins.MISMATCH
    }

    private suspend fun readWanted(): Boolean {
        val marker = layout.wantedMarker
        EngineUpdate.readWanted(marker)?.let { return it }
        val legacy = runCatching { store().getBool(EngineUpdate.WANTED_KEY, false) }.getOrDefault(false)
        if (legacy) writeWanted(true)
        return legacy
    }

    private fun writeWanted(wanted: Boolean) {
        runCatching { EngineUpdate.writeWanted(layout.wantedMarker, wanted) }
            .onFailure { warnLog("[ServeTorr] Could not save the engine install flag: ${it.message}") }
    }

    private fun settle() {
        val installed = hasEngineFiles
        if (!installed) reportMissingEngineFiles()
        val settled = EngineStartPolicy.settledState(
            flags = EngineRuntime.flags,
            version = EngineSource.VERSION,
            port = EngineRuntime.port,
            bindingsAvailable = EngineRuntime.bindingsAvailable,
            installed = installed,
        )
        val failure = EngineRuntime.installFailure
        EngineRuntime.state.value = if (settled == EngineState.NotInstalled && failure != null) EngineState.Failed(failure) else settled
    }

    private fun reportMissingEngineFiles() {
        val layout = layout
        val verified = EngineRuntime.verifiedEngine
        if (!layout.engineRoot.exists() && verified == null) return
        val key = runCatching { TorrentInventory.describeEngineFiles(layout) }.getOrElse { "unreadable: $it" }
        if (key == EngineRuntime.lastMissingReport) return
        EngineRuntime.lastMissingReport = key
        warnLog("[ServeTorr] Engine ${EngineSource.VERSION} files incomplete (verified=${verified != null}): $key")
    }

    private suspend fun isInstalled(): Boolean = withIOContext {
        val dir = engineDir
        if (EngineRuntime.verifiedEngine == dir.absolutePath && hasEngineFiles) return@withIOContext true
        EngineRuntime.verifiedEngine = null

        if (pinsState() == EngineUpdate.Pins.MISMATCH) {
            warnLog("[ServeTorr] Engine ${EngineSource.VERSION} files at ${dir.absolutePath} are from an older build; they need an update")
            return@withIOContext false
        }

        val abi = EngineSource.selectAbi(EngineRuntime.processAbis()) ?: return@withIOContext false
        val files = EngineSource.engineFiles(abi) ?: return@withIOContext false
        val present = files.map { File(dir, it.fileName) to it.sha256 }.filter { (file, _) -> file.isFile }
        if (present.isEmpty()) return@withIOContext false
        files.map { File(dir, it.fileName) }.filterNot { it.isFile }.forEach { file ->
            warnLog("[ServeTorr] Engine file ${file.name} is missing at ${file.absolutePath}; ${TorrentInventory.describeEngineFiles(layout)}")
        }

        val corrupted = present.mapNotNull { (file, sha256) ->
            val size = file.length()
            val actual = if (size == 0L) "empty" else file.sha256()
            if (actual == sha256) null else Triple(file, sha256, actual to size)
        }
        corrupted.forEach { (file, expected, actual) ->
            warnLog(
                "[ServeTorr] Engine file ${file.name} failed verification at ${file.absolutePath} " +
                    "(${actual.second} bytes, sha256 ${actual.first}, expected $expected); removing it"
            )
            file.setWritable(true)
            file.delete()
        }
        if (corrupted.isEmpty() && present.size == files.size) {
            EngineRuntime.verifiedEngine = dir.absolutePath
            EngineSource.pinFingerprint(abi)?.let { pins ->
                runCatching { EngineUpdate.writePins(layout.pinsMarker, pins) }
                    .onFailure { warnLog("[ServeTorr] Could not record the engine pins: ${it.message}") }
            }
            true
        } else {
            false
        }
    }

    private fun File.deleteTree() {
        walkBottomUp().forEach { it.setWritable(true); it.delete() }
    }

    private companion object {
        const val START_TIMEOUT_MILLIS = 5_000L
        const val REUSE_ECHO_MILLIS = 3_000L
        const val METADATA_TIMEOUT_MILLIS = 20_000L
        const val SETTINGS_TIMEOUT_MILLIS = 20_000L
        const val IDLE_POLL_MILLIS = 30_000L
        const val IDLE_WATCHER_PROPERTY = "abrogani.servetorr.engine.idleWatcher"
    }
}
