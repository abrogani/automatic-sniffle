package com.abrogani.streamplay.loader

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import com.flixclusive.core.util.coroutines.FlxDispatchers.Companion.withIOContext
import com.flixclusive.core.util.log.errorLog
import com.flixclusive.core.util.log.infoLog
import com.flixclusive.core.util.log.warnLog
import com.flixclusive.core.util.network.okhttp.request
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import dalvik.system.DexClassLoader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Downloads the pre-built StreamPlay `.cs3` (a zip holding `classes.dex` + `manifest.json`),
 * verifies it against [Cs3Source.SHA256], loads it with a [DexClassLoader] and returns the
 * `MainAPI`s the plugin registered.
 *
 * The plugin is compiled against CloudStream's API, so the CloudStream classes bundled in this
 * `.flx` are used as the parent class loader.
 */
internal class Cs3PluginLoader(
    private val client: OkHttpClient
) {
    private val mutex = Mutex()
    private var loaded: LoadedPlugin? = null

    class LoadedPlugin(
        val plugin: Plugin,
        val apis: List<MainAPI>,
        val classLoader: ClassLoader,
    )

    suspend fun load(context: Context): LoadedPlugin = mutex.withLock {
        loaded?.let { return it }

        val appContext = context.applicationContext ?: context
        val file = ensureFile(appContext)
        val manifest = readManifest(file)

        val classLoader = DexClassLoader(
            file.absolutePath,
            null,
            null,
            Cs3PluginLoader::class.java.classLoader
        )

        val plugin = Class.forName(manifest.getString("pluginClassName"), true, classLoader)
            .getDeclaredConstructor()
            .newInstance() as Plugin

        plugin.filename = file.absolutePath
        if (manifest.optBoolean("requiresResources")) {
            attachResources(appContext, plugin, file)
        }

        withIOContext {
            try {
                plugin.load(appContext)
            } catch (e: LinkageError) {
                // The plugin may be built against newer libraries than the host ships (e.g. coroutines
                // 1.11's `runBlockingK`). It registers its providers before its extractors, so whatever
                // registered before the failure is still usable.
                warnLog("[StreamPlay] Plugin finished loading with a binary incompatibility: $e")
            }
        }

        // The plugin registers its APIs in CloudStream's global holder; pick out only ours.
        val apis = APIHolder.allProviders.filter { it.javaClass.classLoader === classLoader }
        check(apis.isNotEmpty()) { "StreamPlay plugin did not register any providers" }

        infoLog("[StreamPlay] Loaded ${manifest.optString("name")} v${manifest.optInt("version")} (${apis.joinToString { it.name }})")

        LoadedPlugin(plugin, apis, classLoader).also { loaded = it }
    }

    suspend fun unload() = mutex.withLock {
        val current = loaded ?: return
        loaded = null

        runCatching { (current.plugin as BasePlugin).beforeUnload() }
            .onFailure { errorLog(it) }
        current.apis.forEach { runCatching { APIHolder.removePluginMapping(it) } }
        APIHolder.allProviders.removeAll(current.apis.toSet())
        Unit
    }

    private suspend fun ensureFile(context: Context): File = withIOContext {
        val dir = File(context.codeCacheDir, "streamplay").apply { mkdirs() }
        val file = File(dir, "StreamPlay-${Cs3Source.COMMIT}.cs3")

        if (file.exists() && file.sha256() == Cs3Source.SHA256) {
            return@withIOContext file
        }

        // Drop stale/corrupt copies (they are read-only, see below).
        dir.listFiles()?.forEach { it.setWritable(true); it.delete() }

        infoLog("[StreamPlay] Downloading plugin from ${Cs3Source.URL}")
        val temp = File(dir, "download.tmp")
        client.request(url = Cs3Source.URL).execute().use { response ->
            check(response.isSuccessful) { "Failed to download StreamPlay plugin [${response.code}]" }
            temp.outputStream().use { out -> response.body.byteStream().copyTo(out) }
        }

        val actual = temp.sha256()
        if (actual != Cs3Source.SHA256) {
            temp.delete()
            throw SecurityException("StreamPlay plugin checksum mismatch (expected ${Cs3Source.SHA256}, got $actual)")
        }

        check(temp.renameTo(file)) { "Could not store StreamPlay plugin" }
        // Android 14+ refuses to load a writable dex container.
        file.setReadOnly()
        file
    }

    private fun readManifest(file: File): JSONObject =
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("manifest.json")
                ?: throw IllegalStateException("manifest.json missing from StreamPlay plugin")
            JSONObject(zip.getInputStream(entry).bufferedReader().readText())
        }

    /** Same approach CloudStream itself uses so the plugin can read its own `res/`. */
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
}
