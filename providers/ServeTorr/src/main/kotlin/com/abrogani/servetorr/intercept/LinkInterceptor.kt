package com.abrogani.servetorr.intercept

import android.content.Context
import com.abrogani.servetorr.link.TorrentLinkRewriter
import com.flixclusive.core.util.log.errorLog
import com.flixclusive.core.util.log.infoLog
import com.flixclusive.core.util.log.warnLog
import com.flixclusive.provider.ProviderPlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    @Volatile
    private var closed = false

    @Volatile
    private var seenSelf = false

    val isInstalled: Boolean
        get() = active

    fun install(context: Context): Boolean {
        if (active) return true
        if (closed) return false
        val found = try {
            findFlow(context)
        } catch (e: Throwable) {
            errorLog(e)
            null
        }
        if (found == null) {
            warnLog("[ServeTorr] Interception not installed: host provider registry unavailable")
            return false
        }
        return install(found)
    }

    internal fun install(registry: MutableStateFlow<Map<String, ProviderPlugin>>): Boolean {
        synchronized(lock) {
            if (closed) return false
            if (active) return true
            active = true
            flow = registry
            val newScope = CoroutineScope(SupervisorJob() + dispatcher)
            scope = newScope
            newScope.launch {
                registry.collect { map -> onRegistryChanged(registry, map) }
            }
        }
        infoLog("[ServeTorr] Intercepting media links of other providers")
        return true
    }

    private fun onRegistryChanged(
        registry: MutableStateFlow<Map<String, ProviderPlugin>>,
        map: Map<String, ProviderPlugin>,
    ) {
        if (isRegistered(map)) {
            seenSelf = true
        } else {
            if (seenSelf) {
                infoLog("[ServeTorr] This copy of the provider was unregistered; stopping interception")
                stop(registry)
            }
            return
        }
        try {
            synchronized(lock) {
                if (active) registry.update(::wrapAll)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            errorLog("[ServeTorr] Failed to wrap providers")
            errorLog(e)
        }
    }

    fun uninstall() {
        val registry = synchronized(lock) {
            closed = true
            if (!active) return
            flow
        } ?: return
        stop(registry)
    }

    private fun stop(registry: MutableStateFlow<Map<String, ProviderPlugin>>) {
        synchronized(lock) {
            if (!active) return
            active = false
            flow = null
            scope?.cancel()
            scope = null
            try {
                registry.update(::restoreAll)
            } catch (e: Throwable) {
                errorLog(e)
            }
        }
    }

    internal fun wrapAll(map: Map<String, ProviderPlugin>): Map<String, ProviderPlugin> {
        if (!isRegistered(map)) return map
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
            if (plugin !is InterceptingPlugin || plugin.owner !== this) continue
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
        return InterceptingPlugin.create(target, rewriter, isEnabled, owner = this)
    }

    private fun isRegistered(map: Map<String, ProviderPlugin>): Boolean = map.values.any { it === self }

    private fun hasOtherLiveCopy(map: Map<String, ProviderPlugin>): Boolean =
        map.values.any { isCopyOfSelf(it) }

    private fun isCopyOfSelf(plugin: ProviderPlugin): Boolean =
        plugin !== self && plugin.javaClass.name == self.javaClass.name

    private fun unwrapForeign(wrapper: ProviderPlugin): ProviderPlugin? {
        var current: ProviderPlugin = wrapper
        repeat(MAX_UNWRAP_DEPTH) {
            if (!isForeignWrapper(current) && current !is InterceptingPlugin) return current
            current = runCatching { current.javaClass.getMethod("getInner").invoke(current) as? ProviderPlugin }
                .getOrNull() ?: return null
        }
        return null
    }

    internal companion object {
        private const val MAX_UNWRAP_DEPTH = 16
        private val WRAPPER_CLASS_NAME: String = InterceptingPlugin::class.java.name

        fun isForeignWrapper(plugin: ProviderPlugin): Boolean {
            val name = plugin.javaClass.name
            return name == WRAPPER_CLASS_NAME || name.startsWith("$WRAPPER_CLASS_NAME$")
        }
    }
}
