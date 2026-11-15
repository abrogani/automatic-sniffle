package com.abrogani.torrent.engine

import android.content.Context
import com.flixclusive.core.util.log.infoLog
import dalvik.system.DexClassLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest

/**
 * Process-wide engine state. The Go runtime loads once per process and TorrServer can't be restarted after
 * `stopTorrentServer`, so it is never stopped. System properties outlive a provider reload (which brings a
 * fresh copy of this object in a new class loader), letting the next copy find the engine already serving
 * instead of loading `libgojni.so` a second time, which the linker refuses across class loaders.
 */
internal object EngineRuntime {
    private const val PORT_PROPERTY = "abrogani.torrent.engine.port"
    private const val LOADED_PROPERTY = "abrogani.torrent.engine.loaded"

    val state = MutableStateFlow<EngineState>(EngineState.NotInstalled)
    val mutex = Mutex()
    val installMutex = Mutex()

    @Volatile
    var port: Int? = null

    @Volatile
    var verifiedLibrary: String? = null

    private var server: Class<*>? = null

    /** Port of the engine this process started, possibly from an earlier provider instance. */
    val startedPort: Int?
        get() = System.getProperty(PORT_PROPERTY)?.toIntOrNull()

    /** The native library is loaded in this process, but not through this class loader. */
    val loadedElsewhere: Boolean
        get() = server == null && System.getProperty(LOADED_PROPERTY) != null

    /**
     * Loads the bindings if needed and starts TorrServer on [desiredPort]. Returns the real port, or -1 when
     * TorrServer couldn't listen (typically the port is taken). Call with [mutex] held, off the main thread.
     */
    fun start(context: Context, engineDir: File, dataDir: File, desiredPort: Int, bindingsDex: () -> InputStream): Long {
        val server = server ?: load(context, engineDir, bindingsDex)

        val port = invoke {
            server.getMethod("startTorrentServer", String::class.java, Long::class.javaPrimitiveType)
                .invoke(null, dataDir.absolutePath, desiredPort.toLong()) as Long
        }
        if (port > 0) {
            System.setProperty(PORT_PROPERTY, port.toString())
            invoke {
                server.getMethod("addTrackers", String::class.java)
                    .invoke(null, EngineSource.DEFAULT_TRACKERS.joinToString(",\n"))
            }
        }
        return port
    }

    private fun load(context: Context, engineDir: File, bindingsDex: () -> InputStream): Class<*> {
        val dex = prepareBindings(engineDir, bindingsDex)
        val loader = DexClassLoader(
            dex.absolutePath,
            null,
            engineDir.absolutePath,
            TorrentEngine::class.java.classLoader,
        )

        // This build defers loading to go.Seq.load(), which loads libgojni.so through `loader` (the class's
        // defining loader, whose search path is the engine dir) and initialises the Go packages.
        val seq = Class.forName(EngineSource.SEQ_CLASS, true, loader)
        System.setProperty(LOADED_PROPERTY, "true")
        invoke { seq.getMethod("load").invoke(null) }
        invoke { seq.getMethod("setContext", Context::class.java).invoke(null, context) }

        return Class.forName(EngineSource.SERVER_CLASS, true, loader).also {
            server = it
            infoLog("[Torrent] Loaded engine ${EngineSource.VERSION}")
        }
    }

    // Android 14 refuses to load dex files that are writable.
    private fun prepareBindings(engineDir: File, bindingsDex: () -> InputStream): File {
        val dex = File(engineDir, "bindings.dex")
        if (dex.isFile && dex.sha256() == EngineSource.BINDINGS_DEX_SHA256) {
            dex.setReadOnly()
            return dex
        }

        val temp = File(engineDir, "bindings.dex.tmp").also { it.setWritable(true); it.delete() }
        try {
            bindingsDex().use { input -> temp.outputStream().use { input.copyTo(it) } }

            val actual = temp.sha256()
            check(actual == EngineSource.BINDINGS_DEX_SHA256) { "Bundled engine bindings are corrupted ($actual)" }

            dex.setWritable(true)
            dex.delete()
            check(temp.renameTo(dex)) { "Could not store the engine bindings" }
            dex.setReadOnly()
            return dex
        } catch (e: Throwable) {
            temp.delete()
            throw e
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
