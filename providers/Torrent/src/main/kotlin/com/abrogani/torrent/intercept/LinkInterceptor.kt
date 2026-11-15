package com.abrogani.torrent.intercept

import android.content.Context
import com.abrogani.torrent.link.TorrentLinkRewriter
import com.flixclusive.core.util.log.errorLog
import com.flixclusive.core.util.log.infoLog
import com.flixclusive.core.util.log.warnLog
import com.flixclusive.provider.ProviderPlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Replaces every other provider in the host's plugin registry with an [InterceptingPlugin], including ones
 * loaded later, and puts the originals back on [uninstall].
 *
 * Writes go through [MutableStateFlow.update] (a CAS loop), so a host `load`/`remove` racing with us is never lost.
 *
 * Wrappers made by another copy of this provider (an older version whose classes live in a different
 * class loader, left behind e.g. by the host's profile switch, which calls `clearAll()` without `onUnload`)
 * are recognised by class name. They are left alone while another live copy of this provider is registered,
 * and adopted (unwrapped and re-wrapped with ours) once [self] is the only one, so two live copies never
 * fight over the same entry. An interceptor whose [self] has been replaced in the registry by a newer copy
 * retires on its own.
 */
internal class LinkInterceptor(
    private val self: ProviderPlugin,
    private val rewriter: TorrentLinkRewriter,
    private val isEnabled: suspend () -> Boolean,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val findFlow: (Context) -> MutableStateFlow<Map<String, ProviderPlugin>>? =
        HostProviderRegistry::findPluginsFlow,
) {
    private val lock = Any()
    private var flow: MutableStateFlow<Map<String, ProviderPlugin>>? = null
    private var scope: CoroutineScope? = null

    @Volatile
    private var active = false

    val isInstalled: Boolean
        get() = active

    fun install(context: Context): Boolean {
        if (active) return true
        val found = try {
            findFlow(context)
        } catch (e: Throwable) {
            errorLog(e)
            null
        }
        if (found == null) {
            warnLog("[Torrent] Interception not installed: host provider registry unavailable")
            return false
        }
        return install(found)
    }

    internal fun install(registry: MutableStateFlow<Map<String, ProviderPlugin>>): Boolean {
        synchronized(lock) {
            if (active) return true
            active = true
            flow = registry
            val newScope = CoroutineScope(SupervisorJob() + dispatcher)
            scope = newScope
            newScope.launch {
                registry.collect { map ->
                    if (isStale(map)) {
                        infoLog("[Torrent] A newer copy of this provider took over interception")
                        retire()
                        return@collect
                    }
                    try {
                        synchronized(lock) {
                            if (active) registry.update(::wrapAll)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        errorLog("[Torrent] Failed to wrap providers")
                        errorLog(e)
                    }
                }
            }
        }
        infoLog("[Torrent] Intercepting media links of other providers")
        return true
    }

    fun uninstall() {
        val registry = synchronized(lock) {
            if (!active) return
            retire()
            flow.also { flow = null }
        } ?: return
        try {
            registry.update(::restoreAll)
        } catch (e: Throwable) {
            errorLog(e)
        }
    }

    private fun retire() {
        synchronized(lock) {
            active = false
            scope?.cancel()
            scope = null
        }
    }

    /** Returns [map] itself when nothing changes, so the StateFlow doesn't re-emit. */
    internal fun wrapAll(map: Map<String, ProviderPlugin>): Map<String, ProviderPlugin> {
        val adoptForeign = !hasOtherLiveCopy(map)
        var result: MutableMap<String, ProviderPlugin>? = null
        for ((key, plugin) in map) {
            val replacement = wrap(plugin, adoptForeign) ?: continue
            if (result == null) result = LinkedHashMap(map)
            result[key] = replacement
        }
        return result ?: map
    }

    internal fun restoreAll(map: Map<String, ProviderPlugin>): Map<String, ProviderPlugin> {
        var result: MutableMap<String, ProviderPlugin>? = null
        for ((key, plugin) in map) {
            if (plugin !is InterceptingPlugin) continue
            if (result == null) result = LinkedHashMap(map)
            result[key] = plugin.inner
        }
        return result ?: map
    }

    private fun wrap(plugin: ProviderPlugin, adoptForeign: Boolean): ProviderPlugin? {
        if (plugin === self || plugin is InterceptingPlugin || isCopyOfSelf(plugin)) return null
        val target = if (isForeignWrapper(plugin)) {
            if (!adoptForeign) return null
            unwrapForeign(plugin) ?: return null
        } else {
            plugin
        }
        if (target === self || isCopyOfSelf(target)) return null
        return InterceptingPlugin(target, rewriter, isEnabled)
    }

    private fun isStale(map: Map<String, ProviderPlugin>): Boolean =
        map.values.none { it === self } && hasOtherLiveCopy(map)

    private fun hasOtherLiveCopy(map: Map<String, ProviderPlugin>): Boolean =
        map.values.any { it !== self && isCopyOfSelf(it) }

    private fun isCopyOfSelf(plugin: ProviderPlugin): Boolean =
        plugin !== self && plugin.javaClass.name == self.javaClass.name

    private fun isForeignWrapper(plugin: ProviderPlugin): Boolean =
        plugin.javaClass.name == InterceptingPlugin::class.java.name

    private fun unwrapForeign(wrapper: ProviderPlugin): ProviderPlugin? {
        var current: ProviderPlugin = wrapper
        while (isForeignWrapper(current) || current is InterceptingPlugin) {
            current = runCatching { current.javaClass.getMethod("getInner").invoke(current) as? ProviderPlugin }
                .getOrNull() ?: return null
        }
        return current
    }
}
