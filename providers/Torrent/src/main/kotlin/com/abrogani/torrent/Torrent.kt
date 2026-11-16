package com.abrogani.torrent

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.Composable
import com.abrogani.torrent.engine.TorrentEngine
import com.abrogani.torrent.intercept.LinkInterceptor
import com.abrogani.torrent.link.TorrentLinkRewriter
import com.abrogani.torrent.net.LoopbackCleartext
import com.abrogani.torrent.proxy.LinkProxy
import com.abrogani.torrent.settings.DEFAULT_INTERCEPT
import com.abrogani.torrent.settings.DEFAULT_PORT
import com.abrogani.torrent.settings.INTERCEPT_KEY
import com.abrogani.torrent.settings.PORT_KEY
import com.abrogani.torrent.settings.TorrentSettingsScreen
import com.abrogani.torrent.settings.isValidPort
import com.flixclusive.core.util.log.errorLog
import com.flixclusive.core.util.log.warnLog
import com.flixclusive.provider.FlixclusiveProvider
import com.flixclusive.provider.ProviderPlugin
import com.flixclusive.provider.extensions.getBool
import com.flixclusive.provider.extensions.getInt
import com.flixclusive.provider.extensions.setInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@FlixclusiveProvider
class Torrent : ProviderPlugin() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client by lazy { OkHttpClient() }
    private var portChange: Job? = null

    private val engine by lazy {
        TorrentEngine(
            context = application,
            client = client,
            store = { settings },
            bindingsDex = {
                val res = resources ?: throw IllegalStateException("Torrent provider resources are missing")
                res.assets.open(BINDINGS_ASSET)
            },
        )
    }

    private val interceptor by lazy {
        LinkInterceptor(
            self = this,
            rewriter = TorrentLinkRewriter(proxyPort = { proxy.port }, engineInstalled = { engine.hasEngineFiles }),
            isEnabled = { settings.getBool(INTERCEPT_KEY, DEFAULT_INTERCEPT) },
        )
    }

    private val proxy by lazy { LinkProxy(engine) }

    private val application: Context by lazy {
        currentApplication() ?: throw IllegalStateException("Could not find the app's context")
    }

    init {
        scope.launch {
            try {
                LoopbackCleartext.install()
                interceptor.install(application)
                startProxy()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                errorLog(e)
            }
        }
    }

    private suspend fun startProxy() {
        awaitInjectedSettings() ?: return
        proxy.start(settings.getInt(PORT_KEY, DEFAULT_PORT).takeIf { it.isValidPort() } ?: DEFAULT_PORT)
    }

    private suspend fun awaitInjectedSettings(): Unit? {
        repeat(SETTINGS_WAIT_ATTEMPTS) {
            if (runCatching { settings }.isSuccess) return Unit
            delay(SETTINGS_WAIT_STEP_MILLIS)
        }
        warnLog("[Torrent] Settings were never injected; not starting the torrent proxy")
        return null
    }

    @Composable
    override fun SettingsScreen() {
        TorrentSettingsScreen(
            settings = settings,
            engine = engine,
            isInterceptorInstalled = { interceptor.isInstalled },
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
        }
    }

    override suspend fun onUnload(context: Context) {
        scope.cancel()
        interceptor.uninstall()
        proxy.stop()
        LoopbackCleartext.uninstall()

        runCatching { engine.dropAll() }
            .onFailure { warnLog("[Torrent] Could not drop torrents on unload: $it") }
    }

    private companion object {
        const val BINDINGS_ASSET = "torrserver/bindings.dex"
        const val SETTINGS_WAIT_ATTEMPTS = 100
        const val SETTINGS_WAIT_STEP_MILLIS = 100L
        const val PORT_CHANGE_DEBOUNCE_MILLIS = 800L

        @SuppressLint("PrivateApi")
        fun currentApplication(): Context? =
            runCatching {
                Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null) as Context?
            }.onFailure { warnLog("[Torrent] Could not read the current application: $it") }
                .getOrNull()
    }
}
