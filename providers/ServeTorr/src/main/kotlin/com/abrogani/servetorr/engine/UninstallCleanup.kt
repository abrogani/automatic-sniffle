package com.abrogani.servetorr.engine

import android.content.Context
import com.abrogani.servetorr.intercept.HostProviderRegistry
import com.flixclusive.core.util.log.errorLog
import com.flixclusive.core.util.log.infoLog
import com.flixclusive.core.util.log.warnLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

internal object UninstallCheck {
    private val LOADER_PATH = Regex("""(?:zip file|dex file|directory) "([^"]+)"""")

    fun flxPathFromLoaderText(text: String): String? {
        val paths = LOADER_PATH.findAll(text).map { it.groupValues[1] }.toList()
        return paths.firstOrNull { it.endsWith(".flx", ignoreCase = true) } ?: paths.firstOrNull()
    }

    fun installCandidates(flx: File): List<File> {
        val repoDir = flx.parentFile ?: return listOf(flx)
        val userDir = repoDir.parentFile
        val root = userDir?.parentFile
        if (userDir == null || root == null || !userDir.name.startsWith(USER_DIR_PREFIX)) return listOf(flx)

        val otherUsers = root.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(USER_DIR_PREFIX) }
            ?.map { File(File(it, repoDir.name), flx.name) }
            .orEmpty()
        return (listOf(flx) + otherUsers).distinctBy { it.absolutePath }
    }

    fun isUninstalled(
        loadedPluginClasses: Collection<String>?,
        pluginClass: String,
        installCandidates: List<File>?,
        exists: (File) -> Boolean = File::exists,
    ): Boolean {
        if (loadedPluginClasses == null || pluginClass in loadedPluginClasses) return false
        if (installCandidates.isNullOrEmpty()) return false
        return installCandidates.none(exists)
    }

    private const val USER_DIR_PREFIX = "user-"
}

internal object UninstallCleanup {
    private const val FIRST_CHECK_MILLIS = 5_000L
    private const val CONFIRM_CHECK_MILLIS = 20_000L

    fun schedule(context: Context, pluginClass: Class<*>, engine: TorrentEngine) {
        val app = context.applicationContext ?: context
        val flx = flxPathOf(pluginClass.classLoader)?.let(::File)
        if (flx == null) {
            warnLog("[ServeTorr] Could not find this provider's file; engine files won't be removed on uninstall")
            return
        }

        EngineRuntime.scope.launch {
            try {
                delay(FIRST_CHECK_MILLIS)
                if (!uninstalled(app, pluginClass.name, flx)) return@launch
                delay(CONFIRM_CHECK_MILLIS)
                if (!uninstalled(app, pluginClass.name, flx)) return@launch

                infoLog("[ServeTorr] Provider uninstalled (${flx.absolutePath} is gone); removing the torrent engine and its data")
                engine.purge()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                errorLog(e)
            }
        }
    }

    private fun uninstalled(context: Context, pluginClass: String, flx: File): Boolean {
        val loaded = HostProviderRegistry.findPluginsFlow(context)?.value?.values?.map { it.javaClass.name }
        val candidates = runCatching { UninstallCheck.installCandidates(flx) }.getOrNull()
        return UninstallCheck.isUninstalled(loaded, pluginClass, candidates)
    }

    fun flxPathOf(loader: ClassLoader?): String? {
        if (loader == null) return null
        runCatching { UninstallCheck.flxPathFromLoaderText(loader.toString()) }.getOrNull()?.let { return it }
        return runCatching { dexElementPaths(loader) }.getOrNull()
            ?.let { paths -> paths.firstOrNull { it.endsWith(".flx", ignoreCase = true) } ?: paths.firstOrNull() }
    }

    private fun dexElementPaths(loader: ClassLoader): List<String> {
        val pathListField = generateSequence<Class<*>>(loader.javaClass) { it.superclass }
            .firstNotNullOfOrNull { type -> runCatching { type.getDeclaredField("pathList") }.getOrNull() }
            ?: return emptyList()
        pathListField.isAccessible = true
        val pathList = pathListField.get(loader) ?: return emptyList()
        val elementsField = pathList.javaClass.getDeclaredField("dexElements").apply { isAccessible = true }
        val elements = elementsField.get(pathList) as? Array<*> ?: return emptyList()
        return elements.filterNotNull().mapNotNull { element ->
            listOf("path", "zip", "file").firstNotNullOfOrNull { name ->
                runCatching {
                    element.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(element) as? File
                }.getOrNull()?.absolutePath
            }
        }
    }
}
