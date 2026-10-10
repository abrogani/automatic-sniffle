package com.abrogani.streamplay.loader

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.abrogani.streamplay.settings.APPLIED_VERSION_KEY
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
import dev.whyoleg.cryptography.CryptographyProviderApi
import dev.whyoleg.cryptography.CryptographyProviderContainer
import dev.whyoleg.cryptography.CryptographySystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

internal class Cs3PluginLoader(
    private val client: OkHttpClient,
    private val settings: DataStore<Preferences>,
    private val index: suspend () -> RemotePlugin = { PluginIndex.fetch(client) },
) {
    @Volatile
    private var loaded: LoadedPlugin? = null

    private val downloadClient = client.newBuilder()
        .callTimeout(2, TimeUnit.MINUTES)
        .build()

    class LoadedPlugin(
        val plugin: Plugin,
        val apis: List<MainAPI>,
        val classLoader: ClassLoader,
    )

    data class UpdateStatus(
        val installed: Int?,
        val latest: Int,
        val available: Boolean,
        val skipped: Boolean,
    )

    enum class UpdateResult { Updated, UpToDate, RolledBack }

    suspend fun load(context: Context): LoadedPlugin {
        loaded?.let { return it }

        val appContext = context.applicationContext ?: context
        ensureInstalled(appContext)

        return mutex.withLock {
            loaded?.let { return it }

            try {
                loadLocked(appContext)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e

                errorLog(e)
                val failed = currentFileLocked(appContext)?.let { readManifest(it).optInt("version") }
                if (!restorePreviousLocked(appContext)) throw e

                failed?.let { settings.setInt(BAD_VERSION_KEY, it) }
                loadLocked(appContext)
            }
        }
    }

    suspend fun installedVersion(context: Context): Int? = withIOContext {
        mutex.withLock {
            installedFileLocked(context.applicationContext ?: context)
                ?.let { readManifest(it).optInt("version") }
        }
    }

    suspend fun ensureInstalled(context: Context) {
        val appContext = context.applicationContext ?: context

        if (mutex.withLock { currentFileLocked(appContext) } != null) return

        downloadMutex.withLock {
            if (mutex.withLock { currentFileLocked(appContext) } != null) return

            val temp = try {
                download(index(), directory(appContext))
            } catch (e: IOException) {
                // Offline: an older backup is better than nothing.
                if (mutex.withLock { installedFileLocked(appContext) } != null) return

                throw IllegalStateException(
                    "StreamPlay isn't installed yet and couldn't be downloaded. Check your connection.",
                    e
                )
            }

            withContext(NonCancellable) {
                mutex.withLock {
                    val file = File(directory(appContext), FILE_NAME)
                    file.setWritable(true)
                    file.delete()
                    check(temp.renameTo(file)) { "Could not store StreamPlay plugin" }
                    file.setReadOnly()
                }
            }
        }
    }

    suspend fun checkForUpdate(context: Context): UpdateStatus {
        val remote = index()
        val installed = installedVersion(context)
        val newer = installed == null || remote.version > effectiveVersion(installed)
        val bad = settings.getInt(BAD_VERSION_KEY, -1) == remote.version

        return UpdateStatus(
            installed = installed,
            latest = remote.version,
            available = newer && !bad,
            skipped = newer && bad,
        )
    }

    suspend fun update(context: Context, force: Boolean = false): UpdateResult {
        val appContext = context.applicationContext ?: context

        return downloadMutex.withLock {
            val remote = index()
            val bad = settings.getInt(BAD_VERSION_KEY, -1) == remote.version
            if (bad && !force) return UpdateResult.UpToDate

            val installed = installedVersion(appContext)
            if (installed != null && remote.version <= effectiveVersion(installed)) {
                return UpdateResult.UpToDate
            }

            val temp = download(remote, directory(appContext))

            withContext(NonCancellable) {
                mutex.withLock { swapLocked(appContext, remote, temp) }
            }
        }
    }

    suspend fun unload() = mutex.withLock { unloadLocked() }

    private suspend fun effectiveVersion(installed: Int): Int =
        maxOf(installed, settings.getInt(APPLIED_VERSION_KEY, -1))

    private suspend fun swapLocked(context: Context, remote: RemotePlugin, temp: File): UpdateResult {
        val dir = directory(context)
        val current = File(dir, FILE_NAME)
        val previous = File(dir, PREVIOUS_FILE_NAME)

        unloadLocked()
        if (currentFileLocked(context) != null) {
            previous.setWritable(true)
            previous.delete()
            check(current.renameTo(previous)) { "Could not back up the installed plugin" }
        } else {
            current.setWritable(true)
            current.delete()
        }
        check(temp.renameTo(current)) { "Could not store the updated plugin" }
        current.setReadOnly()

        return try {
            loadLocked(context)
            settings.setInt(APPLIED_VERSION_KEY, remote.version)
            settings.setInt(BAD_VERSION_KEY, -1)
            infoLog("[StreamPlay] Updated plugin to v${remote.version}")
            UpdateResult.Updated
        } catch (e: Throwable) {
            if (e is CancellationException) throw e

            errorLog(e)
            unloadLocked()
            restorePreviousLocked(context)
            settings.setInt(BAD_VERSION_KEY, remote.version)
            UpdateResult.RolledBack
        }
    }

    private fun restorePreviousLocked(context: Context): Boolean {
        val dir = directory(context)
        val current = File(dir, FILE_NAME)
        val previous = File(dir, PREVIOUS_FILE_NAME)

        unloadLocked()
        if (!isValid(previous)) return false

        current.setWritable(true)
        current.delete()
        return previous.renameTo(current)
    }

    private suspend fun loadLocked(context: Context): LoadedPlugin {
        loaded?.let { return it }

        initCloudStreamContext(context)
        registerCryptographyProvider()
        warnIfHostCoroutinesAreOld()
        val file = currentFileLocked(context)
            ?: throw IllegalStateException("StreamPlay plugin is missing or corrupted")
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

    // The .flx build drops META-INF, so the library's ServiceLoader lookup finds nothing. Registering
    // must happen before anything reads the registered providers, or the library refuses it.
    @OptIn(CryptographyProviderApi::class)
    private fun registerCryptographyProvider() {
        if (!cryptographyRegistered.compareAndSet(false, true)) return

        runCatching {
            val container = Class.forName("dev.whyoleg.cryptography.providers.jdk.JdkCryptographyProviderContainer")
                .getDeclaredConstructor()
                .newInstance() as CryptographyProviderContainer

            CryptographySystem.registerProvider(container.provider, container.priority)
        }.onFailure { warnLog("[StreamPlay] Could not register the cryptography provider: $it") }
    }

    private fun warnIfHostCoroutinesAreOld() {
        val hasRunBlockingK = runCatching {
            Class.forName("kotlinx.coroutines.BuildersKt").methods.any { it.name == "runBlockingK" }
        }.getOrDefault(true)

        if (!hasRunBlockingK) {
            warnLog("[StreamPlay] The host ships kotlinx-coroutines older than 1.11; sources that use a web view will crash it.")
        }
    }

    private fun directory(context: Context): File {
        File(context.codeCacheDir, "streamplay").takeIf { it.exists() }?.let { legacy ->
            legacy.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }

        return File(context.filesDir, "streamplay").apply { mkdirs() }
    }

    private fun currentFileLocked(context: Context): File? =
        File(directory(context), FILE_NAME).takeIf { isValid(it) }

    // Also heals leftovers: drops a corrupt file and restores the backup after an interrupted swap.
    private fun installedFileLocked(context: Context): File? {
        currentFileLocked(context)?.let { return it }

        val dir = directory(context)
        val current = File(dir, FILE_NAME)
        current.setWritable(true)
        current.delete()

        val previous = File(dir, PREVIOUS_FILE_NAME)
        if (isValid(previous) && previous.renameTo(current)) return current

        return null
    }

    private fun isValid(file: File): Boolean =
        file.isFile && file.length() > 0 && runCatching {
            readManifest(file).also {
                it.getString("pluginClassName")
                it.getInt("version")
            }
        }.isSuccess

    private suspend fun download(remote: RemotePlugin, dir: File): File = withIOContext {
        infoLog("[StreamPlay] Downloading plugin v${remote.version} from ${remote.url}")
        val temp = File(dir, "download.tmp").also { it.delete() }

        try {
            downloadClient.request(url = remote.url).execute().use { response ->
                check(response.isSuccessful) { "Failed to download StreamPlay plugin [${response.code}]" }
                if (response.body.contentLength() > MAX_PLUGIN_BYTES) {
                    throw SecurityException("StreamPlay plugin is larger than expected")
                }

                temp.outputStream().use { out -> response.body.byteStream().copyLimited(out) }
            }

            val actual = temp.sha256()
            if (actual != remote.sha256) {
                throw SecurityException("StreamPlay plugin checksum mismatch (expected ${remote.sha256}, got $actual)")
            }

            temp
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }

    private fun InputStream.copyLimited(out: OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break

            total += read
            if (total > MAX_PLUGIN_BYTES) throw SecurityException("StreamPlay plugin is larger than expected")
            out.write(buffer, 0, read)
        }
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
        const val MAX_PLUGIN_BYTES = 50L * 1024 * 1024

        val cryptographyRegistered = AtomicBoolean(false)
        val mutex = Mutex()
        val downloadMutex = Mutex()
    }
}
