package com.abrogani.mytorrentclient.engine

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.abrogani.mytorrentclient.engine.EngineRuntime.sha256
import com.abrogani.mytorrentclient.engine.EngineSettingsCodec.STORE_KEY
import com.abrogani.mytorrentclient.engine.TorrServerClient.TorrentMetadata
import com.abrogani.mytorrentclient.engine.TorrServerClient.TorrentMetadataTimeout
import com.abrogani.mytorrentclient.link.TorrentFile
import com.abrogani.mytorrentclient.link.TorrentLink
import com.flixclusive.core.util.coroutines.FlxDispatchers.Companion.withIOContext
import com.flixclusive.core.util.log.errorLog
import com.flixclusive.core.util.log.infoLog
import com.flixclusive.core.util.log.warnLog
import com.flixclusive.provider.extensions.getString
import com.flixclusive.provider.extensions.setString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.io.InputStream

internal class TorrentEngine(
    context: Context,
    client: OkHttpClient,
    private val store: () -> DataStore<Preferences>,
    private val bindingsDex: () -> InputStream,
) : TorrentBackend {
    private val appContext = context.applicationContext ?: context
    private val installer = EngineInstaller(client)
    private val api = TorrServerClient(client)

    val state: StateFlow<EngineState> = EngineRuntime.state.asStateFlow()
    val engineVersion: String = EngineSource.VERSION

    private val _settings = MutableStateFlow(EngineSettings())

    val settings: StateFlow<EngineSettings> = _settings.asStateFlow()

    private val settingsLoadMutex = Mutex()

    @Volatile
    private var settingsLoaded = false

    private val rootDir get() = File(appContext.filesDir, "torrent/engine")
    private val engineDir get() = File(rootDir, EngineSource.VERSION)
    private val libraryFile get() = File(engineDir, EngineSource.LIBRARY_NAME)
    private val dataDir get() = File(appContext.cacheDir, "torrent/server")
    private val diskCacheDir get() = File(appContext.cacheDir, "torrent/cache")

    init {
        when (EngineRuntime.state.value) {
            EngineState.NotInstalled, EngineState.Stopped -> EngineRuntime.state.value = when {
                EngineRuntime.port != null -> EngineState.Running(EngineRuntime.port!!)
                EngineRuntime.flags.startedPort != null -> EngineState.Running(EngineRuntime.flags.startedPort!!)
                EngineRuntime.flags.startFailed -> EngineState.Failed(EngineStartPolicy.START_FAILED_MESSAGE)
                libraryFile.isFile -> EngineState.Stopped
                else -> EngineState.NotInstalled
            }
            else -> Unit
        }
    }

    override suspend fun ensureRunning(): Int? {
        EngineRuntime.port?.let { return it }

        val start = EngineRuntime.mutex.withLock {
            EngineRuntime.port?.let { return it }
            EngineRuntime.pendingStart?.takeIf { it.isActive }
                ?: EngineRuntime.scope.async { startReportingFailure() }.also { EngineRuntime.pendingStart = it }
        }
        return start.await()
    }

    private suspend fun startReportingFailure(): Int? =
        try {
            start()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e

            errorLog(e)
            val message = e.message ?: e.javaClass.simpleName
            EngineRuntime.state.value = EngineState.Failed(message)
            throw IllegalStateException(message, e)
        }

    private suspend fun start(): Int? {
        val flags = EngineRuntime.flags
        val started = flags.startedPort
        val alive = started != null && api.waitForEcho(started, REUSE_ECHO_MILLIS)

        when (val decision = EngineStartPolicy.decide(flags, alive, EngineRuntime.bindingsAvailable)) {
            is EngineStartPolicy.Decision.Fail -> throw IllegalStateException(decision.message)
            is EngineStartPolicy.Decision.Reuse -> {
                infoLog("[MyTorrentClient] Reusing the engine on port ${decision.port}")
                applyPersistedSettings(decision.port)
                return running(decision.port)
            }
            EngineStartPolicy.Decision.Start -> Unit
        }

        if (!isInstalled()) {
            if (EngineRuntime.state.value !is EngineState.Installing) {
                EngineRuntime.state.value = EngineState.NotInstalled
            }
            return null
        }

        val result = withIOContext {
            dataDir.mkdirs()
            EngineRuntime.start(appContext, engineDir, dataDir, bindingsDex)
        }
        val port = EngineStartPolicy.recordStart(flags, result)
            ?: throw IllegalStateException(EngineStartPolicy.START_FAILED_MESSAGE)

        if (!api.waitForEcho(port, START_TIMEOUT_MILLIS)) {
            throw IllegalStateException("The torrent engine started on port $port but isn't responding.")
        }

        infoLog("[MyTorrentClient] Engine running on port $port")
        applyPersistedSettings(port)
        return running(port)
    }

    private suspend fun applyPersistedSettings(port: Int) {
        try {
            loadSettings()
            withSettingsTimeout { applySettings(port) }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            warnLog("[MyTorrentClient] Could not apply the engine settings: ${e.message}")
        }
    }

    private fun running(port: Int): Int {
        EngineRuntime.port = port
        EngineRuntime.state.value = EngineState.Running(port)
        return port
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
                        EngineRuntime.state.value = EngineState.Failed(EngineStartPolicy.STOPPED_MESSAGE)
                    }
                }
            }
            throw e
        }
    }

    val hasEngineFiles: Boolean get() = libraryFile.isFile

    override suspend fun files(link: TorrentLink): List<TorrentFile> = metadata(link).files

    override suspend fun infoHash(link: TorrentLink): String? =
        link.infoHash?.takeIf { it.isNotBlank() }?.lowercase() ?: metadata(link).hash.takeIf { it.isNotBlank() }

    private suspend fun metadata(link: TorrentLink): TorrentMetadata = withEngine { port ->
        api.metadata(port, link.source, link.infoHash, METADATA_TIMEOUT_MILLIS, EngineRuntime.dropGeneration)
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
            infoLog("[MyTorrentClient] Engine stopped")
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
                infoLog("[MyTorrentClient] Applied engine settings")
            }

            val added = withIOContext { EngineRuntime.addTrackers(wanted.trackers) }
            if (!added) warnLog("[MyTorrentClient] Engine bindings unavailable; trackers not updated")
        }
    }

    private suspend fun withSettingsTimeout(block: suspend () -> Unit) {
        withTimeoutOrNull(SETTINGS_TIMEOUT_MILLIS) { block() }
            ?: throw IllegalStateException("The torrent engine didn't apply the settings in time.")
    }

    suspend fun install() {
        EngineRuntime.installMutex.withLock {
            try {
                if (isInstalled()) return@withLock settle()

                val abis = EngineRuntime.processAbis()
                val abi = EngineSource.selectAbi(abis)
                if (abi == null) {
                    EngineRuntime.state.value = EngineState.Failed(
                        "This device's CPU (${abis.joinToString()}) isn't supported by the torrent engine."
                    )
                    return@withLock
                }

                EngineRuntime.state.value = EngineState.Installing(0, 0)
                withIOContext {
                    engineDir.mkdirs()
                    installer.install(abi, libraryFile) { downloaded, total ->
                        EngineRuntime.state.value = EngineState.Installing(downloaded, total)
                    }
                    EngineRuntime.verifiedLibrary = libraryFile.absolutePath
                    rootDir.listFiles()
                        ?.filter { it.name != EngineSource.VERSION }
                        ?.forEach { it.deleteTree() }
                }
                settle()
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    settle()
                    throw e
                }

                errorLog(e)
                EngineRuntime.state.value = EngineState.Failed(
                    "Couldn't install the torrent engine: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    suspend fun remove() {
        stop()
        EngineRuntime.installMutex.withLock {
            withIOContext {
                rootDir.deleteTree()
                dataDir.deleteTree()
                diskCacheDir.deleteTree()
                EngineRuntime.verifiedLibrary = null
            }
            settle()
        }
    }

    private fun settle() {
        val running = EngineRuntime.port ?: EngineRuntime.flags.startedPort
        EngineRuntime.state.value = when {
            running != null -> EngineState.Running(running)
            EngineRuntime.flags.startFailed && libraryFile.isFile ->
                EngineState.Failed(EngineStartPolicy.START_FAILED_MESSAGE)
            libraryFile.isFile -> EngineState.Stopped
            else -> EngineState.NotInstalled
        }
    }

    private suspend fun isInstalled(): Boolean = withIOContext {
        val file = libraryFile
        if (!file.isFile || file.length() == 0L) return@withIOContext false
        if (EngineRuntime.verifiedLibrary == file.absolutePath) return@withIOContext true

        val abi = EngineSource.selectAbi(EngineRuntime.processAbis())
        val expected = abi?.let { EngineSource.LIBRARY_SHA256[it] }
        if (expected != null && file.sha256() == expected) {
            EngineRuntime.verifiedLibrary = file.absolutePath
            true
        } else {
            warnLog("[MyTorrentClient] Installed engine is corrupted; removing it")
            file.setWritable(true)
            file.delete()
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
    }
}
