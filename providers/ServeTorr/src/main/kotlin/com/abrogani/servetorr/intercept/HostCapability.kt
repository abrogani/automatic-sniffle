package com.abrogani.servetorr.intercept

import android.content.Context
import com.flixclusive.core.util.log.warnLog
import com.flixclusive.provider.ProviderPlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

internal data class ProviderRow(val id: String, val ownerId: String, val mediaLinkEnabled: Boolean)

internal enum class StreamsToggle {
    ON,
    OFF,
    UNREADABLE,
    ;

    val intercepts: Boolean
        get() = this != OFF

    companion object {
        fun of(enabled: Boolean?): StreamsToggle =
            when (enabled) {
                true -> ON
                false -> OFF
                null -> UNREADABLE
            }
    }
}

internal class Memo<T : Any>(private val ttlMillis: Long, private val clock: () -> Long) {
    private val mutex = Mutex()

    @Volatile
    private var entry: Pair<Long, T>? = null

    fun put(value: T) {
        entry = clock() to value
    }

    suspend fun get(load: suspend () -> T): T {
        fresh()?.let { return it }
        return mutex.withLock { fresh() ?: load().also(::put) }
    }

    private fun fresh(): T? = entry?.takeIf { clock() - it.first < ttlMillis }?.second
}

internal class RetryGate(private val intervalMillis: Long, private val clock: () -> Long) {
    private var last: Long? = null

    @Synchronized
    fun tryPass(): Boolean {
        val now = clock()
        val previous = last
        if (previous != null && now - previous < intervalMillis) return false
        last = now
        return true
    }
}

internal class HostCapability(
    private val context: () -> Context,
    private val self: ProviderPlugin,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val memo = Memo<StreamsToggle>(MEMO_MILLIS, clock)
    private val lookupGate = RetryGate(LOOKUP_RETRY_MILLIS, clock)
    private val warned = AtomicBoolean(false)

    @Volatile
    private var plugins: StateFlow<Map<String, ProviderPlugin>>? = null

    @Volatile
    private var selfId: String? = null

    @Volatile
    private var dao: Any? = null

    @Volatile
    private var session: Any? = null

    private class Handles(val selfId: String, val dao: Any, val session: Any?)

    suspend fun current(): StreamsToggle = memo.get { read() }

    fun changes(): Flow<StreamsToggle> =
        flow {
            awaitRegistration()
            while (true) {
                observed()?.let { source ->
                    source
                        .catch { e ->
                            if (e is CancellationException) throw e
                            unreadable("watching the toggle failed: $e")
                        }
                        .collect { emit(it) }
                }
                emit(current())
                delay(POLL_MILLIS)
            }
        }.distinctUntilChanged().onEach(memo::put)

    private suspend fun awaitRegistration() {
        if (selfId != null) return
        if (plugins == null && lookupGate.tryPass()) lookup()
        val registry = plugins ?: return
        withTimeoutOrNull(REGISTRATION_WAIT_MILLIS) { registry.first { selfIdIn(it) != null } }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observed(): Flow<StreamsToggle>? {
        val handles = handles() ?: return null
        val session = handles.session ?: return null
        val users = HostLinkCache.invokeNoArg(session, CURRENT_USER) as? Flow<*> ?: return null
        val rowFlow = handles.dao.javaClass.methods.firstOrNull {
            it.name == ROW_FLOW && it.parameterTypes.size == 2 && Flow::class.java.isAssignableFrom(it.returnType)
        } ?: return null
        return users
            .map { it as? String }
            .distinctUntilChanged()
            .flatMapLatest { user ->
                if (user == null) {
                    flow { emit(read()) }
                } else {
                    (rowFlow.invoke(handles.dao, handles.selfId, user) as Flow<*>).map { row ->
                        rowOf(row)?.let { StreamsToggle.of(it.mediaLinkEnabled) } ?: read()
                    }
                }
            }
    }

    private suspend fun read(): StreamsToggle =
        try {
            val handles = handles() ?: return unreadable("host provider database not found")
            val user = handles.session?.let { currentUserOf(it) }
            val own = user?.let { rowOf(HostLinkCache.callSuspend(handles.dao, ONE_PROVIDER, handles.selfId, it)) }
            if (own != null) {
                StreamsToggle.of(own.mediaLinkEnabled)
            } else {
                val rows = (HostLinkCache.callSuspend(handles.dao, ALL_PROVIDERS) as? List<*>).orEmpty().mapNotNull(::rowOf)
                StreamsToggle.of(decide(rows, handles.selfId, user)).also {
                    if (it == StreamsToggle.UNREADABLE) unreadable("no installed_providers row for this provider")
                }
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            unreadable(e.toString())
        }

    private fun handles(): Handles? {
        val knownDao = dao
        val knownSession = session
        val id = selfId ?: plugins?.let(::selfIdIn)
        if (id != null && knownDao != null && knownSession != null) return Handles(id, knownDao, knownSession)
        if (lookupGate.tryPass()) lookup()
        val resolvedId = selfId ?: plugins?.let(::selfIdIn) ?: return null
        val resolvedDao = dao ?: return null
        return Handles(resolvedId, resolvedDao, session)
    }

    private fun lookup() {
        val app: Any = context().let { it.applicationContext ?: it }
        if (plugins == null) plugins = HostProviderRegistry.findPluginsFlow(context())
        if (dao != null && session != null) return
        val component = HostProviderRegistry.generatedComponent(app) ?: return
        if (dao == null) {
            dao = HostLinkCache.findDatabaseIn(component)
                ?.takeIf { HostLinkCache.noArgMethod(it.javaClass, PROVIDER_DAO) != null }
                ?.let { HostLinkCache.invokeNoArg(it, PROVIDER_DAO) }
        }
        if (session == null) session = findSession(component)
    }

    private fun selfIdIn(flow: StateFlow<Map<String, ProviderPlugin>>): String? = selfIdIn(flow.value)

    private fun selfIdIn(map: Map<String, ProviderPlugin>): String? =
        map.entries.firstOrNull { it.value === self }?.key?.also { selfId = it }

    private fun findSession(component: Any): Any? {
        for (holder in listOf(component) + HostLinkCache.shardsOf(component)) {
            for (field in HostLinkCache.instanceFields(holder.javaClass)) {
                val value = HostLinkCache.read(field, holder) ?: continue
                val candidate = if (HostLinkCache.isMemoizing(value)) HostLinkCache.memoized(value) else value
                if (candidate != null && HostLinkCache.noArgMethod(candidate.javaClass, CURRENT_USER) != null) return candidate
            }
        }
        return null
    }

    private suspend fun currentUserOf(session: Any): String? {
        val flow = HostLinkCache.invokeNoArg(session, CURRENT_USER) as? Flow<*> ?: return null
        return withTimeoutOrNull(USER_LOOKUP_MILLIS) { flow.first() as? String }
    }

    private fun unreadable(reason: String): StreamsToggle {
        if (warned.compareAndSet(false, true)) {
            warnLog("[ServeTorr] Couldn't read the Streams toggle; interception stays on: $reason")
        }
        return StreamsToggle.UNREADABLE
    }

    companion object {
        private const val PROVIDER_DAO = "installedProviderDao"
        private const val ALL_PROVIDERS = "getAll"
        private const val ONE_PROVIDER = "get"
        private const val ROW_FLOW = "getAsFlow"
        private const val CURRENT_USER = "getCurrentUserId"
        private const val USER_LOOKUP_MILLIS = 2_000L
        private const val MEMO_MILLIS = 1_500L
        private const val LOOKUP_RETRY_MILLIS = 5_000L
        private const val POLL_MILLIS = 5_000L
        private const val REGISTRATION_WAIT_MILLIS = 30_000L

        fun decide(rows: List<ProviderRow>, selfId: String, currentUser: String?): Boolean? {
            val own = rows.filter { it.id == selfId }
            if (own.isEmpty()) return null
            own.firstOrNull { it.ownerId == currentUser }?.let { return it.mediaLinkEnabled }
            return own.any { it.mediaLinkEnabled }
        }

        private fun rowOf(row: Any?): ProviderRow? {
            row ?: return null
            return ProviderRow(
                id = HostLinkCache.property(row, "id") as? String ?: return null,
                ownerId = HostLinkCache.property(row, "ownerId") as? String ?: return null,
                mediaLinkEnabled = (HostLinkCache.property(row, "isMediaLinkEnabled") ?: HostLinkCache.property(row, "mediaLinkEnabled")) as? Boolean ?: return null,
            )
        }
    }
}
