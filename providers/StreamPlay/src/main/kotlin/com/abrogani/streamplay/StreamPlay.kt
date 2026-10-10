package com.abrogani.streamplay

import android.content.Context
import com.abrogani.streamplay.loader.Cs3PluginLoader
import com.flixclusive.provider.FlixclusiveProvider
import com.flixclusive.provider.ProviderPlugin
import com.flixclusive.provider.capability.CrossMatchProviderApi
import com.flixclusive.provider.capability.MediaLinkProviderApi
import okhttp3.OkHttpClient

@FlixclusiveProvider
class StreamPlay : ProviderPlugin() {
    private val loader by lazy { Cs3PluginLoader(client = OkHttpClient()) }

    private var api: StreamPlayApi? = null

    // `manifest` is injected after construction, so the API must be built lazily.
    private fun getApi(context: Context) = api ?: StreamPlayApi(
        context = context.applicationContext ?: context,
        providerId = id,
        loader = loader,
    ).also { api = it }

    override suspend fun getCrossMatchApi(context: Context): CrossMatchProviderApi = getApi(context)

    override suspend fun getMediaLinkApi(context: Context): MediaLinkProviderApi = getApi(context)

    override suspend fun onUnload(context: Context) {
        loader.unload()
        api = null
    }
}
