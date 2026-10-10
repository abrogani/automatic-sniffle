package com.abrogani.streamplay

import android.content.Context
import com.flixclusive.provider.FlixclusiveProvider
import com.flixclusive.provider.ProviderPlugin
import com.flixclusive.provider.capability.CrossMatchProviderApi
import com.flixclusive.provider.capability.MediaLinkProviderApi
import com.phisher98.StreamPlayPlugin

@FlixclusiveProvider
class StreamPlay : ProviderPlugin() {
    private val cs3Plugin by lazy { StreamPlayPlugin() }
    private var isInitialized = false

    // `manifest` is injected after construction, so this must stay lazy.
    private val api by lazy { StreamPlayApi(providerId = id) }

    private fun initialize(context: Context) {
        if (!isInitialized) {
            cs3Plugin.load(context)
            isInitialized = true
        }
    }

    override suspend fun getCrossMatchApi(context: Context): CrossMatchProviderApi = api

    override suspend fun getMediaLinkApi(context: Context): MediaLinkProviderApi {
        initialize(context)
        return api
    }
}
