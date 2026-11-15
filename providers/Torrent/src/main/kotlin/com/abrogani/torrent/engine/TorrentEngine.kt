package com.abrogani.torrent.engine

import android.content.Context
import android.os.Build
import com.abrogani.torrent.engine.EngineRuntime.sha256
import com.abrogani.torrent.link.TorrentFile
import com.abrogani.torrent.link.TorrentLink
import com.flixclusive.core.util.coroutines.FlxDispatchers.Companion.withIOContext
import com.flixclusive.core.util.log.errorLog
import com.flixclusive.core.util.log.infoLog
import com.flixclusive.core.util.log.warnLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.io.InputStream

internal class TorrentEngine(
    context: Context,
    client: OkHttpClient,
    private val port: suspend () -> Int,
    private val bindingsDex: () -> InputStream,
) : TorrentBackend {
    private val appContext = context.applicationContext ?: context
    private val installer = EngineInstaller(client)
    private val api = TorrServerClient(client)

    val state: StateFlow<EngineState> = EngineRuntime.state.asStateFlow()
    val engineVersion: String = EngineSource.VERSION

    private val rootDir get() = File(appContext.filesDir, "torrent/engine")
    private val engineDir get() = File(rootDir, EngineSource.VERSION)
    private val libraryFile get() = File(engineDir, EngineSource.LIBRARY_NAME)
    private val dataDir get() = File(appContext.cacheDir, "torrent/server")

    init {
        when (EngineRuntime.state.value) {
            EngineState.NotInstalled, EngineState.Stopped -> EngineRuntime.state.value = when {
                EngineRuntime.port != null -> EngineState.Running(EngineRuntime.port!!)
                EngineRuntime.startedPort != null -> EngineState.Running(EngineRuntime.startedPort!!)
                libraryFile.isFile -> EngineState.Stopped
                else -> EngineState.NotInstalled
            }
            else -> Unit
        }
    }

    override suspend fun ensureRunning(): Int? {
        EngineRuntime.port?.let { return it }

        return EngineRuntime.mutex.withLock {
            EngineRuntime.port?.let { return@withLock it }

            try {
                startLocked()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e

                errorLog(e)
                val message = e.message ?: e.javaClass.simpleName
                EngineRuntime.state.value = EngineState.Failed(message)
                throw IllegalStateException(message, e)
            }
        }
    }

    private suspend fun startLocked(): Int? {
        EngineRuntime.startedPort?.let { started ->
            if (api.echo(started)) return running(started)
        }

        if (!isInstalled()) {
            if (EngineRuntime.state.value !is EngineState.Installing) {
                EngineRuntime.state.value = EngineState.NotInstalled
            }
            return null
        }

        val desired = port()
        if (api.echo(desired)) {
            infoLog("[Torrent] An engine is already serving on port $desired; reusing it")
            return running(desired)
        }

        if (EngineRuntime.loadedElsewhere) {
            throw IllegalStateException("The torrent engine stopped responding. Restart the app to start it again.")
        }

        val started = withIOContext {
            dataDir.mkdirs()
            EngineRuntime.start(appContext, engineDir, dataDir, desired, bindingsDex)
        }
        if (started <= 0) {
            throw IllegalStateException("Port $desired is in use by another app. Pick a different port in the settings.")
        }

        val port = started.toInt()
        if (!api.waitForEcho(port, START_TIMEOUT_MILLIS)) {
            throw IllegalStateException("The torrent engine started on port $port but isn't responding.")
        }

        infoLog("[Torrent] Engine running on port $port")
        return running(port)
    }

    private fun running(port: Int): Int {
        EngineRuntime.port = port
        EngineRuntime.state.value = EngineState.Running(port)
        return port
    }

    /** Whether the engine's files are on disk, without verifying them. */
    val hasEngineFiles: Boolean get() = libraryFile.isFile

    override suspend fun files(link: TorrentLink): List<TorrentFile> {
        val port = ensureRunning() ?: throw IllegalStateException("The torrent engine isn't installed")
        return api.files(port, link.source, link.infoHash, METADATA_TIMEOUT_MILLIS)
    }

    /** Downloads and verifies the engine for this device. Never throws except on cancellation; failures land in [state]. */
    suspend fun install() {
        EngineRuntime.installMutex.withLock {
            try {
                if (isInstalled()) return@withLock settle()

                val abi = EngineSource.selectAbi(Build.SUPPORTED_ABIS.toList())
                if (abi == null) {
                    EngineRuntime.state.value = EngineState.Failed(
                        "This device's CPU (${Build.SUPPORTED_ABIS.joinToString()}) isn't supported by the torrent engine."
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

    /** Deletes the engine. An engine already running in this process keeps running until the app restarts. */
    suspend fun remove() {
        EngineRuntime.installMutex.withLock {
            withIOContext {
                rootDir.deleteTree()
                EngineRuntime.verifiedLibrary = null
                if (EngineRuntime.port == null && EngineRuntime.startedPort == null) dataDir.deleteTree()
            }
            settle()
        }
    }

    /** Drops every torrent the engine holds. Does nothing when the engine isn't running. */
    suspend fun dropAll() {
        val port = EngineRuntime.port ?: EngineRuntime.startedPort ?: return
        try {
            api.list(port).mapNotNull { it.hash }.forEach { api.drop(port, it) }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            warnLog("[Torrent] Could not drop torrents: $e")
        }
    }

    private fun settle() {
        val running = EngineRuntime.port ?: EngineRuntime.startedPort
        EngineRuntime.state.value = when {
            running != null -> EngineState.Running(running)
            libraryFile.isFile -> EngineState.Stopped
            else -> EngineState.NotInstalled
        }
    }

    private suspend fun isInstalled(): Boolean = withIOContext {
        val file = libraryFile
        if (!file.isFile || file.length() == 0L) return@withIOContext false
        if (EngineRuntime.verifiedLibrary == file.absolutePath) return@withIOContext true

        val abi = EngineSource.selectAbi(Build.SUPPORTED_ABIS.toList())
        val expected = abi?.let { EngineSource.LIBRARY_SHA256[it] }
        if (expected != null && file.sha256() == expected) {
            EngineRuntime.verifiedLibrary = file.absolutePath
            true
        } else {
            warnLog("[Torrent] Installed engine is corrupted; removing it")
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
        const val METADATA_TIMEOUT_MILLIS = 20_000L
    }
}
