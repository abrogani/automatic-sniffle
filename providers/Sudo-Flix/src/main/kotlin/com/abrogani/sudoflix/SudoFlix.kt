package com.abrogani.sudoflix

import android.content.Context
import com.abrogani.sudoflix.api.SudoFlixApi
import com.flixclusive.provider.FlixclusiveProvider
import com.flixclusive.provider.ProviderPlugin
import com.flixclusive.provider.capability.CrossMatchProviderApi
import com.flixclusive.provider.capability.MediaLinkProviderApi
import okhttp3.OkHttpClient

@FlixclusiveProvider
class SudoFlix : ProviderPlugin() {
    private val client by lazy { OkHttpClient() }

    // `manifest` is injected after construction, so this must stay lazy.
    private val api by lazy { SudoFlixApi(client = client, providerId = id) }

    override suspend fun getCrossMatchApi(context: Context): CrossMatchProviderApi = api

    override suspend fun getMediaLinkApi(context: Context): MediaLinkProviderApi = api
}
