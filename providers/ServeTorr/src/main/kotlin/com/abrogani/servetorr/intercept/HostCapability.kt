package com.abrogani.servetorr.intercept

import android.content.Context
import com.flixclusive.core.util.log.warnLog
import com.flixclusive.provider.ProviderPlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

internal data class ProviderRow(val id: String, val ownerId: String, val mediaLinkEnabled: Boolean)

internal object HostCapability {
    private const val PROVIDER_DAO = "installedProviderDao"
    private const val ALL_PROVIDERS = "getAll"
    private const val CURRENT_USER = "getCurrentUserId"
    private const val USER_LOOKUP_MILLIS = 2_000L

    private val warned = AtomicBoolean(false)

    @Volatile
    private var cache: Pair<Any, Any?>? = null

    suspend fun isMediaLinkEnabled(context: Context, self: ProviderPlugin): Boolean? =
        try {
            val selfId = HostProviderRegistry.findPluginsFlow(context)?.value
                ?.entries?.firstOrNull { it.value === self }?.key
                ?: return null
            val (database, session) = lookup(context) ?: return unknown("host provider database not found")
            val dao = HostLinkCache.invokeNoArg(database, PROVIDER_DAO) ?: return unknown("$PROVIDER_DAO() returned nothing")
            val rows = (HostLinkCache.callSuspend(dao, ALL_PROVIDERS) as? List<*>).orEmpty().mapNotNull(::rowOf)
            val currentUser = session?.let { currentUserOf(it) }
            decide(rows, selfId, currentUser)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            unknown(e.toString())
        }

    fun decide(rows: List<ProviderRow>, selfId: String, currentUser: String?): Boolean? {
        val own = rows.filter { it.id == selfId }
        if (own.isEmpty()) return null
        own.firstOrNull { it.ownerId == currentUser }?.let { return it.mediaLinkEnabled }
        return own.any { it.mediaLinkEnabled }
    }

    private fun lookup(context: Context): Pair<Any, Any?>? {
        cache?.let { return it }
        val app: Any = context.applicationContext ?: context
        val component = HostProviderRegistry.generatedComponent(app) ?: return null
        val database = HostLinkCache.findDatabaseIn(component)
            ?.takeIf { HostLinkCache.noArgMethod(it.javaClass, PROVIDER_DAO) != null }
            ?: return null
        return (database to findSession(component)).also { cache = it }
    }

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

    private fun rowOf(row: Any?): ProviderRow? {
        row ?: return null
        return ProviderRow(
            id = HostLinkCache.property(row, "id") as? String ?: return null,
            ownerId = HostLinkCache.property(row, "ownerId") as? String ?: return null,
            mediaLinkEnabled = (HostLinkCache.property(row, "isMediaLinkEnabled") ?: HostLinkCache.property(row, "mediaLinkEnabled")) as? Boolean ?: return null,
        )
    }

    private fun unknown(reason: String): Boolean? {
        if (warned.compareAndSet(false, true)) {
            warnLog("[ServeTorr] Couldn't read the Links capability toggle; interception stays on: $reason")
        }
        return null
    }
}
