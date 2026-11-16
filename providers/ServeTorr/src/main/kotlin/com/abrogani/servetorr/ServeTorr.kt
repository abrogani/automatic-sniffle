package com.abrogani.servetorr

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.Composable
import com.abrogani.servetorr.engine.EngineState
import com.abrogani.servetorr.engine.PluginRegistration
import com.abrogani.servetorr.engine.TorrentEngine
import com.abrogani.servetorr.engine.UninstallCleanup
import com.abrogani.servetorr.intercept.HostLinkCache
import com.abrogani.servetorr.intercept.HostProviderRegistry
import com.abrogani.servetorr.intercept.HostCapability
import com.abrogani.servetorr.intercept.StaleLinkPolicy
import com.abrogani.servetorr.intercept.StreamsToggle
import com.abrogani.servetorr.intercept.LinkInterceptor
import com.abrogani.servetorr.link.TorrentLinkRewriter
import com.abrogani.servetorr.net.LoopbackCleartext
import com.abrogani.servetorr.net.NetworkPolicy
import com.abrogani.servetorr.proxy.LinkProxy
import com.abrogani.servetorr.settings.DEFAULT_IDLE_STOP_MINUTES
import com.abrogani.servetorr.settings.DEFAULT_PORT
import com.abrogani.servetorr.settings.DEFAULT_WIFI_ONLY
import com.abrogani.servetorr.settings.IDLE_STOP_MINUTES_KEY
import com.abrogani.servetorr.settings.PORT_KEY
import com.abrogani.servetorr.settings.TorrentSettingsScreen
import com.abrogani.servetorr.settings.WIFI_ONLY_KEY
import com.abrogani.servetorr.settings.isValidPort
import com.flixclusive.core.util.log.errorLog
import com.flixclusive.core.util.log.infoLog
import com.flixclusive.core.util.log.warnLog
import com.flixclusive.provider.FlixclusiveProvider
import com.flixclusive.provider.ProviderPlugin
import com.flixclusive.provider.capability.MediaLinkProviderApi
import com.flixclusive.provider.extensions.getBool
import com.flixclusive.provider.extensions.getBoolAsFlow
import com.flixclusive.provider.extensions.getInt
import com.flixclusive.provider.extensions.setInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@FlixclusiveProvider
class ServeTorr : ProviderPlugin() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> errorLog(e) })
    private val client by lazy { OkHttpClient() }
    private var portChange: Job? = null

    private val engine by lazy {
        TorrentEngine(
            context = application,
            client = client,
            store = { settings },
            idleStopMinutes = { settings.getInt(IDLE_STOP_MINUTES_KEY, DEFAULT_IDLE_STOP_MINUTES) },
        )
    }

    private val interceptor by lazy {
        LinkInterceptor(
            self = this,
            rewriter = TorrentLinkRewriter(proxyPort = { proxy.port }, engineInstalled = { engine.hasEngineFiles }),
            isEnabled = { interceptionEnabled() },
        )
    }

    private val capability by lazy { HostCapability(context = { application }, self = this) }

    private val streamsToggle = MutableStateFlow<StreamsToggle?>(null)

    private val proxy by lazy { LinkProxy(engine, blockReason = network::blockReason) }

    private val network by lazy { NetworkPolicy(application, wifiOnly = { settings.getBool(WIFI_ONLY_KEY, DEFAULT_WIFI_ONLY) }) }

    private val application: Context by lazy {
        currentApplication() ?: throw IllegalStateException("Could not find the app's context")
    }

    init {
        scope.launch {
            try {
                LoopbackCleartext.install()
                interceptor.install(application)
                scope.launch { watchStreamsToggle() }
                startProxy()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                errorLog(e)
            }
        }
    }

    private suspend fun startProxy() {
        awaitInjectedSettings() ?: return
        network.watch { scope.launch { stopEngineIfBlocked() } }
        scope.launch {
            settings.getBoolAsFlow(WIFI_ONLY_KEY, DEFAULT_WIFI_ONLY)
                .filter { it }
                .collect { stopEngineIfBlocked() }
        }
        proxy.start(settings.getInt(PORT_KEY, DEFAULT_PORT).takeIf { it.isValidPort() } ?: DEFAULT_PORT)
        scope.launch { updateEngineIfNeeded() }
        scope.launch { purgeStaleLinksOnInstall() }
    }

    private suspend fun purgeStaleLinksOnInstall() {
        engine.state
            .map { it is EngineState.Stopped || it is EngineState.Running }
            .distinctUntilChanged()
            .withIndex()
            .filter { it.index == 0 || it.value }
            .collect { purgeStaleLinks() }
    }

    private suspend fun purgeStaleLinks() {
        val policy = StaleLinkPolicy.forInterception(interceptionEnabled(), proxy.port, engine.hasEngineFiles)
        HostLinkCache.purge(application, policy)
    }

    private suspend fun watchStreamsToggle() {
        capability.changes().collect { toggle ->
            val previous = streamsToggle.value
            streamsToggle.value = toggle
            StaleLinkPolicy.onToggleChange(previous, toggle, proxy.port, engine.hasEngineFiles)
                ?.let { HostLinkCache.purge(application, it) }
        }
    }

    private suspend fun interceptionEnabled(): Boolean = capability.current().intercepts

    override suspend fun getMediaLinkApi(context: Context): MediaLinkProviderApi = PlaceholderLinkApi

    private suspend fun updateEngineIfNeeded() {
        engine.autoUpdate(
            blockReason = network::blockReason,
            wakeups = merge(network.allowedAgain, settings.getBoolAsFlow(WIFI_ONLY_KEY, DEFAULT_WIFI_ONLY)),
            stillCurrent = { PluginRegistration.isRegistered(this) { HostProviderRegistry.findPluginsFlow(application)?.value?.values } },
        )
    }

    private suspend fun stopEngineIfBlocked() {
        if (engine.state.value !is EngineState.Running) return
        val reason = network.blockReason() ?: return
        infoLog("[ServeTorr] Stopping the engine: $reason")
        runCatching { engine.stop() }
            .onFailure { warnLog("[ServeTorr] Could not stop the engine: $it") }
    }

    private suspend fun awaitInjectedSettings(): Unit? {
        repeat(SETTINGS_WAIT_ATTEMPTS) {
            if (runCatching { settings }.isSuccess) return Unit
            delay(SETTINGS_WAIT_STEP_MILLIS)
        }
        warnLog("[ServeTorr] Settings were never injected; not starting the torrent proxy")
        return null
    }

    @Composable
    override fun SettingsScreen() {
        TorrentSettingsScreen(
            settings = settings,
            engine = engine,
            isInterceptorInstalled = { interceptor.isInstalled },
            streamsToggle = streamsToggle,
            proxyPort = { proxy.port },
            onPortChange = ::changeProxyPort,
            backgroundScope = scope,
        )
    }

    private fun changeProxyPort(port: Int) {
        portChange?.cancel()
        portChange = scope.launch {
            settings.setInt(PORT_KEY, port)
            delay(PORT_CHANGE_DEBOUNCE_MILLIS)
            proxy.start(port)
            purgeStaleLinks()
        }
    }

    override suspend fun onUnload(context: Context) {
        scope.cancel()
        interceptor.uninstall()
        proxy.stop()
        runCatching { network.close() }
        LoopbackCleartext.uninstall()

        runCatching { engine.dropAll() }
            .onFailure { warnLog("[ServeTorr] Could not drop torrents on unload: $it") }

        runCatching { UninstallCleanup.schedule(context, javaClass, engine) }
            .onFailure { warnLog("[ServeTorr] Could not schedule the uninstall check: $it") }
    }

    private companion object {
        const val SETTINGS_WAIT_ATTEMPTS = 100
        const val SETTINGS_WAIT_STEP_MILLIS = 100L
        const val PORT_CHANGE_DEBOUNCE_MILLIS = 800L

        @SuppressLint("PrivateApi")
        fun currentApplication(): Context? =
            runCatching {
                Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null) as Context?
            }.onFailure { warnLog("[ServeTorr] Could not read the current application: $it") }
                .getOrNull()
    }
}
