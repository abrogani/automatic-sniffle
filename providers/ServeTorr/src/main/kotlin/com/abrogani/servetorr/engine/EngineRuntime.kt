package com.abrogani.servetorr.engine

import android.content.Context
import android.os.Build
import android.os.Process
import com.flixclusive.core.util.log.infoLog
import dalvik.system.DexClassLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

internal object EngineRuntime {
    private const val BINDINGS_PROPERTY = "abrogani.servetorr.engine.bindings"

    val flags = ProcessFlags.system

    val state = MutableStateFlow<EngineState>(EngineState.NotInstalled)

    val mutex = Mutex()
    val installLock = EngineInstallLock()

    val updatePending = MutableStateFlow(false)

    @Volatile
    var installFailure: String? = null

    val settingsMutex = Mutex()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var pendingStart: Deferred<Int?>? = null

    @Volatile
    var port: Int? = null

    @Volatile
    var verifiedEngine: String? = null

    val activeResolutions = AtomicInteger()

    var idleWatcher: Job? = null

    @Volatile
    var lastMissingReport: String? = null

    val dropGeneration = MutableStateFlow(0)

    private var server: Class<*>? = null

    private val bindings: Class<*>?
        get() = server ?: (System.getProperties()[BINDINGS_PROPERTY] as? Class<*>)

    val bindingsAvailable: Boolean get() = bindings != null

    fun processAbis(): List<String> =
        EngineSource.processAbis(
            is64Bit = Process.is64Bit(),
            abis64 = Build.SUPPORTED_64_BIT_ABIS.toList(),
            abis32 = Build.SUPPORTED_32_BIT_ABIS.toList(),
        )

    fun start(context: Context, engineDir: File, dataDir: File): Long {
        val server = bindings ?: load(context, engineDir)

        return try {
            invoke {
                server.getMethod("startTorrentServer", String::class.java, Long::class.javaPrimitiveType)
                    .invoke(null, dataDir.absolutePath, 0L) as Long
            }
        } catch (e: Throwable) {
            flags.startFailed = true
            throw e
        }
    }

    fun stop(): Boolean {
        val server = bindings ?: return false
        invoke { server.getMethod("stopTorrentServer").invoke(null) }
        return true
    }

    fun addTrackers(trackers: List<String>): Boolean {
        val server = bindings ?: return false
        invoke {
            server.getMethod("addTrackers", String::class.java)
                .invoke(null, trackers.joinToString(",\n"))
        }
        return true
    }

    private fun load(context: Context, engineDir: File): Class<*> {
        val dex = File(engineDir, EngineSource.DEX_NAME)
        check(dex.isFile) { "The engine bindings are missing. Download the engine again." }
        dex.setReadOnly()
        val loader = DexClassLoader(
            dex.absolutePath,
            null,
            engineDir.absolutePath,
            TorrentEngine::class.java.classLoader,
        )

        val seq = Class.forName(EngineSource.SEQ_CLASS, true, loader)
        invoke { seq.getMethod("load").invoke(null) }
        flags.recordLoaded(EngineSource.VERSION)
        invoke { seq.getMethod("setContext", Context::class.java).invoke(null, context) }

        return Class.forName(EngineSource.SERVER_CLASS, true, loader).also {
            server = it
            System.getProperties()[BINDINGS_PROPERTY] = it
            infoLog("[ServeTorr] Loaded engine ${EngineSource.VERSION}")
        }
    }

    private inline fun <T> invoke(block: () -> T): T =
        try {
            block()
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }

    fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
