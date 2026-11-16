package com.abrogani.servetorr.intercept

import android.content.Context
import com.abrogani.servetorr.link.ProxyUrl
import com.abrogani.servetorr.link.TorrentLink
import com.flixclusive.core.util.log.infoLog
import com.flixclusive.core.util.log.warnLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

internal class StaleLinkPolicy(
    private val currentProxyPort: Int?,
    private val purgeRawTorrents: Boolean,
) {
    fun isStale(url: String): Boolean {
        val proxyPort = proxyPortOf(url)
        if (proxyPort != null) return currentProxyPort != null && proxyPort != currentProxyPort
        return purgeRawTorrents && currentProxyPort != null && TorrentLink.parse(url) != null
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"

        fun proxyPortOf(url: String): Int? {
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
            if (!uri.scheme.equals("http", ignoreCase = true)) return null
            if (uri.host != LOOPBACK || uri.port <= 0) return null
            if (uri.rawPath?.startsWith(ProxyUrl.PATH_PREFIX) != true) return null
            val query = uri.rawQuery ?: return null
            ProxyUrl.parseQuery(query) ?: return null
            return uri.port
        }
    }
}

internal object HostLinkCache {
    private const val DATABASE_HINT = "database"
    private const val LINK_DAO = "cachedMediaLinkDao"
    private const val USER_DAO = "userDao"
    private const val ALL_USERS = "getAll"
    private const val STREAMS_BY_OWNER = "getStreamsByOwner"
    private const val DELETE_STREAM = "deleteStream"
    private const val INSTANCE_FIELD = "instance"
    private const val SENTINEL_FIELD = "UNINITIALIZED"

    private val lock = Mutex()
    private val warned = AtomicBoolean(false)

    private data class StreamRow(
        val url: String,
        val ownerId: String,
        val mediaId: String?,
        val providerId: String?,
        val seasonNumber: Int?,
        val episodeNumber: Int?,
    ) {
        val group get() = listOf(mediaId, providerId, seasonNumber, episodeNumber)
    }

    suspend fun purgeStaleTorrentLinks(context: Context, currentProxyPort: Int?, engineInstalled: Boolean): Int {
        if (currentProxyPort == null) return 0
        return try {
            lock.withLock {
                val app: Any = context.applicationContext ?: context
                val component = HostProviderRegistry.generatedComponent(app)
                    ?: return@withLock failed("${app.javaClass.name} exposes no Hilt component")
                val database = findDatabaseIn(component)
                    ?: return@withLock failed("no link database in ${component.javaClass.name}")
                val removed = purgeIn(database, StaleLinkPolicy(currentProxyPort, engineInstalled))
                if (removed > 0) infoLog("[ServeTorr] Removed $removed cached links that pointed at raw torrents or an old proxy port")
                removed
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            failed(e.toString())
        }
    }

    fun findDatabaseIn(component: Any): Any? {
        val holders = listOf(component) + shardsOf(component)

        val named = holders.flatMap { holder ->
            instanceFields(holder.javaClass)
                .filter { it.name.lowercase().contains(DATABASE_HINT) }
                .mapNotNull { read(it, holder) }
        }
        for (value in named.sortedBy { if (isMemoizing(it)) 0 else 1 }) {
            val candidate = if (isMemoizing(value)) memoizedOrGet(value) else value
            candidate?.takeIf(::isLinkDatabase)?.let { return it }
        }

        for (holder in holders) {
            for (field in instanceFields(holder.javaClass)) {
                val value = read(field, holder) ?: continue
                if (!isMemoizing(value)) continue
                memoized(value)?.takeIf(::isLinkDatabase)?.let { return it }
            }
        }
        return null
    }

    suspend fun purgeIn(database: Any, policy: StaleLinkPolicy): Int {
        val linkDao = invokeNoArg(database, LINK_DAO) ?: error("$LINK_DAO() returned nothing")
        val userDao = invokeNoArg(database, USER_DAO) ?: error("$USER_DAO() returned nothing")
        val owners = (callSuspend(userDao, ALL_USERS) as? List<*>).orEmpty()
            .mapNotNull { user -> user?.let { property(it, "id") as? String } }
            .distinct()

        var removed = 0
        for (owner in owners) {
            val rows = (callSuspend(linkDao, STREAMS_BY_OWNER, owner) as? List<*>).orEmpty().mapNotNull(::rowOf)
            val staleGroups = rows.filter { policy.isStale(it.url) }.map { it.group }.toSet()
            if (staleGroups.isEmpty()) continue
            for (row in rows.filter { it.group in staleGroups }) {
                callSuspend(linkDao, DELETE_STREAM, row.url, row.ownerId)
                removed++
            }
        }
        return removed
    }

    private fun rowOf(stream: Any?): StreamRow? {
        stream ?: return null
        return StreamRow(
            url = property(stream, "url") as? String ?: return null,
            ownerId = property(stream, "ownerId") as? String ?: return null,
            mediaId = property(stream, "mediaId") as? String,
            providerId = property(stream, "providerId") as? String,
            seasonNumber = property(stream, "seasonNumber") as? Int,
            episodeNumber = property(stream, "episodeNumber") as? Int,
        )
    }

    private fun failed(reason: String): Int {
        if (warned.compareAndSet(false, true)) {
            warnLog("[ServeTorr] Couldn't clean up cached torrent links: $reason")
        }
        return 0
    }

    private fun isLinkDatabase(candidate: Any): Boolean =
        noArgMethod(candidate.javaClass, LINK_DAO) != null && noArgMethod(candidate.javaClass, USER_DAO) != null

    private suspend fun callSuspend(target: Any, name: String, vararg args: Any?): Any? {
        val method = target.javaClass.methods.firstOrNull {
            it.name == name &&
                it.parameterTypes.size == args.size + 1 &&
                it.parameterTypes.last() == Continuation::class.java
        } ?: error("${target.javaClass.name}.$name not found")
        method.isAccessible = true
        return suspendCoroutineUninterceptedOrReturn { continuation ->
            try {
                method.invoke(target, *args, continuation)
            } catch (e: InvocationTargetException) {
                throw e.targetException ?: e
            }
        }
    }

    private fun property(target: Any, name: String): Any? {
        val getter = "get" + name.replaceFirstChar { it.uppercaseChar() }
        noArgMethod(target.javaClass, getter)?.let { method ->
            return runCatching {
                method.isAccessible = true
                method.invoke(target)
            }.getOrNull()
        }
        return findField(target.javaClass, name)?.let { read(it, target) }
    }

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

    private fun noArgMethod(type: Class<*>, name: String): Method? =
        generateSequence(type) { it.superclass }
            .firstNotNullOfOrNull { runCatching { it.getDeclaredMethod(name) }.getOrNull() }
            ?: runCatching { type.getMethod(name) }.getOrNull()

    private fun invokeNoArg(target: Any, name: String): Any? =
        runCatching {
            val method = noArgMethod(target.javaClass, name) ?: return null
            method.isAccessible = true
            method.invoke(target)
        }.getOrNull()
}
