package com.abrogani.torrent

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.Composable
import com.abrogani.torrent.engine.TorrentEngine
import com.abrogani.torrent.intercept.LinkInterceptor
import com.abrogani.torrent.link.TorrentLinkRewriter
import com.abrogani.torrent.net.LoopbackCleartext
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@FlixclusiveProvider
class Torrent : ProviderPlugin() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client by lazy { OkHttpClient() }

    // `settings` and `resources` are injected after construction; these lambdas only run later.
    private val engine by lazy {
        TorrentEngine(
            context = application,
            client = client,
            port = { settings.getInt(PORT_KEY, DEFAULT_PORT).takeIf { it.isValidPort() } ?: DEFAULT_PORT },
            bindingsDex = {
                val res = resources ?: throw IllegalStateException("Torrent provider resources are missing")
                res.assets.open(BINDINGS_ASSET)
            },
        )
    }

    private val interceptor by lazy {
        LinkInterceptor(
            self = this,
            rewriter = TorrentLinkRewriter(backend = engine),
            isEnabled = { settings.getBool(INTERCEPT_KEY, DEFAULT_INTERCEPT) },
        )
    }

    private val application: Context by lazy {
        currentApplication() ?: throw IllegalStateException("Could not find the app's context")
    }

    init {
        // There is no onLoad hook and this provider has no capability the host would call, so the
        // interception starts here. The host registers this instance right after constructing it.
        scope.launch {
            try {
                LoopbackCleartext.install()
                interceptor.install(application)
                startEngineIfInstalled()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                errorLog(e)
            }
        }
    }

    /**
     * The host replays cached links without asking providers again, so an engine that only started
     * on the first torrent link would leave cached loopback links dead after an app restart.
     */
    private suspend fun startEngineIfInstalled() {
        awaitInjectedSettings() ?: return
        if (!settings.getBool(INTERCEPT_KEY, DEFAULT_INTERCEPT) || !engine.hasEngineFiles) return

        runCatching { engine.ensureRunning() }
            .onFailure { if (it is CancellationException) throw it }
    }

    // `settings` is a lateinit the host sets right after construction, which can race this coroutine.
    private suspend fun awaitInjectedSettings(): Unit? {
        repeat(SETTINGS_WAIT_ATTEMPTS) {
            if (runCatching { settings }.isSuccess) return Unit
            delay(SETTINGS_WAIT_STEP_MILLIS)
        }
        warnLog("[Torrent] Settings were never injected; not starting the engine early")
        return null
    }

    @Composable
    override fun SettingsScreen() {
        TorrentSettingsScreen(
            settings = settings,
            engine = engine,
            isInterceptorInstalled = interceptor.isInstalled,
        )
    }

    override suspend fun onUnload(context: Context) {
        scope.cancel()
        interceptor.uninstall()
        LoopbackCleartext.uninstall()

        // The engine keeps running: Go can't be loaded twice in one process, so a reload reuses it.
        runCatching { engine.dropAll() }
            .onFailure { warnLog("[Torrent] Could not drop torrents on unload: $it") }
    }

    private companion object {
        const val BINDINGS_ASSET = "torrserver/bindings.dex"
        const val SETTINGS_WAIT_ATTEMPTS = 100
        const val SETTINGS_WAIT_STEP_MILLIS = 100L

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
