package com.abrogani.torrent.intercept

import android.content.Context
import com.flixclusive.core.util.log.warnLog
import com.flixclusive.provider.ProviderPlugin
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Finds the host's `ProviderRepositoryImpl.pluginsMap` through the Hilt singleton component.
 *
 * Shapes this relies on (Flixclusive 3.0.x, Hilt/Dagger 2.59.2, no R8):
 * - the application is a `Hilt_*` subclass exposing `generatedComponent()`;
 * - the component (`SingletonCImpl`) holds `provideProviderRepositoryProvider = DoubleCheck.provider(providerRepositoryImplProvider)`;
 * - `providerRepositoryImplProvider` is an unscoped `SwitchingProvider`: calling `get()` on it would build a
 *   second, empty repository, so only memoizing providers (`DoubleCheck`/`SingleCheck`) are ever unwrapped.
 */
internal object HostProviderRegistry {
    private const val REPOSITORY_HINT = "providerrepository"
    private const val PLUGINS_FIELD = "pluginsMap"
    private const val INSTANCE_FIELD = "instance"
    private const val SENTINEL_FIELD = "UNINITIALIZED"

    fun findPluginsFlow(context: Context): MutableStateFlow<Map<String, ProviderPlugin>>? {
        val app: Any = context.applicationContext ?: context
        val component = generatedComponent(app)
        if (component == null) {
            warnLog("[Torrent] Host internals not found: ${app.javaClass.name} exposes no Hilt component; torrent links from other providers won't be intercepted")
            return null
        }

        val flow = findPluginsFlowIn(component)
        if (flow == null) {
            warnLog("[Torrent] Host internals not found: no provider registry in ${component.javaClass.name}; torrent links from other providers won't be intercepted")
        }
        return flow
    }

    fun generatedComponent(app: Any): Any? {
        invokeNoArg(app, "generatedComponent")?.let { return it }
        val manager = invokeNoArg(app, "componentManager") ?: return null
        return invokeNoArg(manager, "generatedComponent")
    }

    fun findPluginsFlowIn(component: Any): MutableStateFlow<Map<String, ProviderPlugin>>? {
        val holders = listOf(component) + shardsOf(component)

        val named = holders.flatMap { holder ->
            instanceFields(holder.javaClass)
                .filter { it.name.lowercase().contains(REPOSITORY_HINT) }
                .mapNotNull { read(it, holder) }
        }
        // Memoizing providers first: they hold the real singleton, anything else might be a factory.
        for (value in named.sortedBy { if (isMemoizing(it)) 0 else 1 }) {
            val candidate = if (isMemoizing(value)) memoizedOrGet(value) else value
            candidate?.let(::pluginsFlowOf)?.let { return it }
        }

        for (holder in holders) {
            for (field in instanceFields(holder.javaClass)) {
                val value = read(field, holder) ?: continue
                if (!isMemoizing(value)) continue
                val instance = memoized(value) ?: continue
                pluginsFlowOf(instance)?.let { return it }
            }
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    fun pluginsFlowOf(candidate: Any): MutableStateFlow<Map<String, ProviderPlugin>>? {
        val field = allFields(candidate.javaClass).firstOrNull { it.name == PLUGINS_FIELD } ?: return null
        val flow = read(field, candidate) as? MutableStateFlow<*> ?: return null
        if (flow.value !is Map<*, *>) return null
        return flow as MutableStateFlow<Map<String, ProviderPlugin>>
    }

    /** Large Dagger components spill bindings into `*Shard` fields; look one level into those too. */
    private fun shardsOf(component: Any): List<Any> =
        instanceFields(component.javaClass)
            .filter { it.type.name.contains("Shard") }
            .mapNotNull { read(it, component) }

    private fun isMemoizing(value: Any): Boolean =
        sentinelOf(value.javaClass) != null && findField(value.javaClass, INSTANCE_FIELD) != null

    private fun memoized(provider: Any): Any? {
        val sentinel = sentinelOf(provider.javaClass) ?: return null
        val field = findField(provider.javaClass, INSTANCE_FIELD) ?: return null
        val instance = read(field, provider) ?: return null
        return instance.takeIf { it !== sentinel }
    }

    /** Name-matched scoped binding: initialising it builds the one true singleton, so `get()` is safe here. */
    private fun memoizedOrGet(provider: Any): Any? = memoized(provider) ?: invokeNoArg(provider, "get")

    private fun sentinelOf(type: Class<*>): Any? {
        val field = allFields(type).firstOrNull { it.name == SENTINEL_FIELD && Modifier.isStatic(it.modifiers) }
            ?: return null
        return read(field, null)
    }

    private fun instanceFields(type: Class<*>): List<Field> =
        allFields(type).filterNot { Modifier.isStatic(it.modifiers) }

    private fun findField(type: Class<*>, name: String): Field? =
        allFields(type).firstOrNull { it.name == name && !Modifier.isStatic(it.modifiers) }

    private fun allFields(type: Class<*>): List<Field> {
        val fields = mutableListOf<Field>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            runCatching { fields += current.declaredFields }
            current = current.superclass
        }
        return fields
    }

    private fun read(field: Field, owner: Any?): Any? =
        runCatching {
            field.isAccessible = true
            field.get(owner)
        }.getOrNull()

    private fun invokeNoArg(target: Any, name: String): Any? =
        runCatching {
            val method = generateSequence<Class<*>>(target.javaClass) { it.superclass }
                .firstNotNullOfOrNull { type ->
                    runCatching { type.getDeclaredMethod(name) }.getOrNull()
                }
                ?: target.javaClass.getMethod(name)
            method.isAccessible = true
            method.invoke(target)
        }.getOrNull()
}
