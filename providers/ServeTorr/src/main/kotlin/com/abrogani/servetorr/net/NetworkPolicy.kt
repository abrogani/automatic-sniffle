package com.abrogani.servetorr.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.flixclusive.core.util.log.warnLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class Transport { WIFI, ETHERNET, CELLULAR, VPN, OTHER }

internal object WifiCheck {
    fun isOnWifi(active: Set<Transport>?, others: List<Set<Transport>>): Boolean {
        if (active == null) return false
        if (active.isLocal()) return true
        if (Transport.VPN !in active) return false
        if (active.any { it != Transport.VPN }) return false
        return others.any { Transport.VPN !in it && it.isLocal() }
    }

    private fun Set<Transport>.isLocal() = Transport.WIFI in this || Transport.ETHERNET in this
}

internal class NetworkPolicy(
    context: Context,
    private val wifiOnly: suspend () -> Boolean,
) {
    private val connectivity = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var pending: Job? = null

    suspend fun blockReason(): String? {
        if (!wifiOnly()) return null
        val onWifi =
            try {
                isOnWifi()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                warnLog("[ServeTorr] Couldn't check the network type: $e")
                return CHECK_FAILED
            }
        return if (onWifi) null else NOT_ON_WIFI
    }

    fun watch(onBlocked: () -> Unit) {
        val manager = connectivity ?: return
        synchronized(lock) {
            if (callback != null) return
            val cb =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = changed(onBlocked)

                    override fun onLost(network: Network) = changed(onBlocked)

                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = changed(onBlocked)
                }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    manager.registerDefaultNetworkCallback(cb)
                } else {
                    manager.registerNetworkCallback(
                        NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                        cb,
                    )
                }
                callback = cb
            } catch (e: RuntimeException) {
                warnLog("[ServeTorr] Couldn't watch network changes: $e")
            }
        }
    }

    fun close() {
        synchronized(lock) {
            pending?.cancel()
            pending = null
            callback?.let { cb -> runCatching { connectivity?.unregisterNetworkCallback(cb) } }
            callback = null
        }
        scope.cancel()
    }

    private fun changed(onBlocked: () -> Unit) {
        synchronized(lock) {
            if (callback == null) return
            pending?.cancel()
            pending =
                scope.launch {
                    delay(DEBOUNCE_MILLIS)
                    try {
                        if (blockReason() != null) onBlocked()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        warnLog("[ServeTorr] Couldn't apply the Wi-Fi only setting: $e")
                    }
                }
        }
    }

    @Suppress("DEPRECATION")
    private fun isOnWifi(): Boolean {
        val manager = connectivity ?: throw IllegalStateException("No connectivity service")
        val active = manager.activeNetwork?.let(manager::getNetworkCapabilities)?.transports()
        val others =
            if (active != null && Transport.VPN in active && active.size == 1) {
                manager.allNetworks.mapNotNull { manager.getNetworkCapabilities(it)?.transports() }
            } else {
                emptyList()
            }
        return WifiCheck.isOnWifi(active, others)
    }

    private fun NetworkCapabilities.transports(): Set<Transport> =
        buildSet {
            if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(Transport.WIFI)
            if (hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(Transport.ETHERNET)
            if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(Transport.CELLULAR)
            if (hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(Transport.VPN)
            if (isEmpty()) add(Transport.OTHER)
        }

    companion object {
        const val NOT_ON_WIFI = "Wi-Fi only is on and this device isn't on Wi-Fi."
        const val CHECK_FAILED = "Wi-Fi only is on and the network type couldn't be checked."
        private const val DEBOUNCE_MILLIS = 2_000L
    }
}
