package com.abrogani.streamplay.loader

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.abrogani.streamplay.settings.BAD_VERSION_KEY
import com.flixclusive.core.util.coroutines.FlxDispatchers.Companion.withIOContext
import com.flixclusive.core.util.log.errorLog
import com.flixclusive.core.util.log.infoLog
import com.flixclusive.core.util.log.warnLog
import com.flixclusive.core.util.network.okhttp.request
import com.flixclusive.provider.extensions.getInt
import com.flixclusive.provider.extensions.setInt
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import dalvik.system.DexClassLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.zip.ZipFile

internal class Cs3PluginLoader(
    private val client: OkHttpClient,
    private val settings: DataStore<Preferences>,
    private val index: suspend () -> RemotePlugin = { PluginIndex.fetch(client) },
) {
    private val mutex = Mutex()
    private var loaded: LoadedPlugin? = null

    class LoadedPlugin(
        val plugin: Plugin,
        val apis: List<MainAPI>,
        val classLoader: ClassLoader,
    )

    data class UpdateStatus(
        val installed: Int?,
        val latest: Int,
        val available: Boolean,
    )

    suspend fun load(context: Context): LoadedPlugin = mutex.withLock {
        loadLocked(context.applicationContext ?: context)
    }

    suspend fun installedVersion(context: Context): Int? = withIOContext {
        installedFile(context)?.let { readManifest(it).optInt("version") }
    }

    suspend fun ensureInstalled(context: Context) {
        mutex.withLock {
            val appContext = context.applicationContext ?: context
            installedFile(appContext) ?: install(appContext)
        }
    }

    suspend fun checkForUpdate(context: Context): UpdateStatus {
        val remote = index()
        val installed = installedVersion(context)
        val bad = settings.getInt(BAD_VERSION_KEY, -1)

        return UpdateStatus(
            installed = installed,
            latest = remote.version,
            available = (installed == null || remote.version > installed) && remote.version != bad,
        )
    }

    suspend fun update(context: Context): Boolean = mutex.withLock {
        val appContext = context.applicationContext ?: context
        val remote = index()
        val installed = installedFile(appContext)?.let { readManifest(it).optInt("version") }
        if (installed != null && remote.version <= installed) return false

        val dir = directory(appContext)
        val temp = download(remote, dir)
        val current = File(dir, FILE_NAME)
        val previous = File(dir, PREVIOUS_FILE_NAME)

        unloadLocked()
        if (current.exists()) {
            previous.setWritable(true)
            previous.delete()
            check(current.renameTo(previous)) { "Could not back up the installed plugin" }
        }
        check(temp.renameTo(current)) { "Could not store the updated plugin" }
        current.setReadOnly()

        try {
            loadLocked(appContext)
            infoLog("[StreamPlay] Updated plugin v$installed -> v${remote.version}")
            true
        } catch (e: Throwable) {
            if (e is CancellationException) throw e

            errorLog(e)
            unloadLocked()
            current.setWritable(true)
            current.delete()
            if (previous.exists()) previous.renameTo(current)
            settings.setInt(BAD_VERSION_KEY, remote.version)
            false
        }
    }

    suspend fun unload() = mutex.withLock { unloadLocked() }

    private suspend fun loadLocked(context: Context): LoadedPlugin {
        loaded?.let { return it }

        initCloudStreamContext(context)
        val file = installedFile(context) ?: install(context)
        val manifest = readManifest(file)

        val classLoader = DexClassLoader(
            file.absolutePath,
            null,
            null,
            Cs3PluginLoader::class.java.classLoader
        )

        try {
            val plugin = Class.forName(manifest.getString("pluginClassName"), true, classLoader)
                .getDeclaredConstructor()
                .newInstance() as Plugin

            plugin.filename = file.absolutePath
            if (manifest.optBoolean("requiresResources")) {
                attachResources(context, plugin, file)
            }

            withIOContext {
                try {
                    plugin.load(context)
                } catch (e: LinkageError) {
                    // Providers register before extractors, so what loaded before the failure still works.
                    warnLog("[StreamPlay] Plugin finished loading with a binary incompatibility: $e")
                }
            }

            val apis = APIHolder.allProviders.filter { it.javaClass.classLoader === classLoader }
            check(apis.isNotEmpty()) { "StreamPlay plugin did not register any providers" }

            infoLog("[StreamPlay] Loaded ${manifest.optString("name")} v${manifest.optInt("version")} (${apis.joinToString { it.name }})")

            return LoadedPlugin(plugin, apis, classLoader).also { loaded = it }
        } catch (e: Throwable) {
            APIHolder.allProviders.removeAll { it.javaClass.classLoader === classLoader }
            throw e
        }
    }

    private fun unloadLocked() {
        val current = loaded ?: return
        loaded = null

        runCatching { (current.plugin as BasePlugin).beforeUnload() }
            .onFailure { errorLog(it) }
        current.apis.forEach { runCatching { APIHolder.removePluginMapping(it) } }
        APIHolder.allProviders.removeAll(current.apis.toSet())
    }

    private fun initCloudStreamContext(context: Context) {
        // Reflection: the compiler rejects these as prerelease-only APIs.
        runCatching {
            Class.forName("com.lagradost.api.ContextHelper_androidKt")
                .getMethod("setCtx", WeakReference::class.java)
                .invoke(null, WeakReference(context))
        }.onFailure { warnLog("[StreamPlay] Could not set CloudStream's context holder: $it") }

        runCatching {
            val companion = Class.forName("com.lagradost.cloudstream3.CloudStreamApp\$Companion")
            val instance = Class.forName("com.lagradost.cloudstream3.CloudStreamApp")
                .getField("Companion")
                .get(null)

            companion.getDeclaredMethod("setContext", Context::class.java)
                .apply { isAccessible = true }
                .invoke(instance, context)
        }.onFailure { warnLog("[StreamPlay] Could not set CloudStreamApp.context: $it") }
    }

    private fun directory(context: Context): File {
        File(context.codeCacheDir, "streamplay").takeIf { it.exists() }?.let { legacy ->
            legacy.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }

        return File(context.filesDir, "streamplay").apply { mkdirs() }
    }

    private fun installedFile(context: Context): File? =
        File(directory(context), FILE_NAME).takeIf { it.exists() }

    private suspend fun install(context: Context): File {
        val dir = directory(context)
        val temp = download(index(), dir)
        val file = File(dir, FILE_NAME)

        check(temp.renameTo(file)) { "Could not store StreamPlay plugin" }
        // Android 14+ refuses to load a writable dex file.
        file.setReadOnly()
        return file
    }

    private suspend fun download(remote: RemotePlugin, dir: File): File = withIOContext {
        infoLog("[StreamPlay] Downloading plugin v${remote.version} from ${remote.url}")
        val temp = File(dir, "download.tmp").also { it.delete() }

        client.request(url = remote.url).execute().use { response ->
            check(response.isSuccessful) { "Failed to download StreamPlay plugin [${response.code}]" }
            temp.outputStream().use { out -> response.body.byteStream().copyTo(out) }
        }

        val actual = temp.sha256()
        if (actual != remote.sha256) {
            temp.delete()
            throw SecurityException("StreamPlay plugin checksum mismatch (expected ${remote.sha256}, got $actual)")
        }

        temp
    }

    private fun readManifest(file: File): JSONObject =
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("manifest.json")
                ?: throw IllegalStateException("manifest.json missing from StreamPlay plugin")
            JSONObject(zip.getInputStream(entry).bufferedReader().readText())
        }

    @Suppress("DiscouragedPrivateApi")
    private fun attachResources(context: Context, plugin: Plugin, file: File) {
        try {
            val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
            AssetManager::class.java.getMethod("addAssetPath", String::class.java)
                .invoke(assets, file.absolutePath)

            plugin.resources = Resources(
                assets,
                context.resources.displayMetrics,
                context.resources.configuration
            )
        } catch (e: Throwable) {
            warnLog("[StreamPlay] Could not attach plugin resources: ${e.message}")
        }
    }

    private fun File.sha256(): String {
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

    private companion object {
        const val FILE_NAME = "StreamPlay.cs3"
        const val PREVIOUS_FILE_NAME = "StreamPlay.cs3.previous"
    }
}
