package com.abrogani.streamplay

import android.content.Context
import androidx.compose.runtime.Composable
import com.abrogani.streamplay.loader.Cs3PluginLoader
import com.abrogani.streamplay.settings.AUTO_UPDATE_KEY
import com.abrogani.streamplay.settings.UpdateScreen
import com.flixclusive.core.util.android.showToast
import com.flixclusive.core.util.coroutines.FlxDispatchers.Companion.withMainContext
import com.flixclusive.core.util.log.warnLog
import com.flixclusive.provider.FlixclusiveProvider
import com.flixclusive.provider.ProviderPlugin
import com.flixclusive.provider.capability.CrossMatchProviderApi
import com.flixclusive.provider.capability.MediaLinkProviderApi
import com.flixclusive.provider.extensions.getBool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicBoolean

@FlixclusiveProvider
class StreamPlay : ProviderPlugin() {
    private val loader by lazy { Cs3PluginLoader(client = OkHttpClient(), settings = settings) }
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    private var api: StreamPlayApi? = null

    @Composable
    override fun SettingsScreen() {
        UpdateScreen(settings = settings, loader = loader)
    }

    override suspend fun getCrossMatchApi(context: Context): CrossMatchProviderApi = getApi(context)

    override suspend fun getMediaLinkApi(context: Context): MediaLinkProviderApi = getApi(context)

    override suspend fun onUnload(context: Context) {
        checkStarted.set(false)
        scope.cancel()
        loader.unload()
        api = null
    }

    private fun getApi(context: Context): StreamPlayApi {
        val appContext = context.applicationContext ?: context
        startUpdateCheck(appContext)

        return api ?: StreamPlayApi(
            context = appContext,
            providerId = id,
            loader = loader,
        ).also { api = it }
    }

    private fun startUpdateCheck(context: Context) {
        if (!checkStarted.compareAndSet(false, true)) return

        scope.launch {
            runCatching {
                loader.ensureInstalled(context)

                val status = loader.checkForUpdate(context)
                if (!status.available) return@runCatching

                if (settings.getBool(AUTO_UPDATE_KEY, true)) {
                    loader.update(context)
                } else {
                    withMainContext {
                        context.showToast("StreamPlay update available (v${status.installed} -> v${status.latest}). Open the provider's settings to update.")
                    }
                }
            }.onFailure { warnLog("[StreamPlay] Update check failed: $it") }
        }
    }

    private companion object {
        val checkStarted = AtomicBoolean(false)
    }
}
