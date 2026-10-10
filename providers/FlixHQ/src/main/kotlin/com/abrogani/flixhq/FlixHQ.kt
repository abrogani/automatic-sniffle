package com.abrogani.flixhq

import android.content.Context
import com.abrogani.flixhq.api.FlixHQApi
import com.abrogani.flixhq.api.FlixHQMediaLinkApi
import com.flixclusive.provider.FlixclusiveProvider
import com.flixclusive.provider.ProviderPlugin
import com.flixclusive.provider.capability.CrossMatchProviderApi
import com.flixclusive.provider.capability.MediaLinkProviderApi
import com.flixclusive.provider.capability.MediaMetadataProviderApi
import com.flixclusive.provider.capability.SearchProviderApi
import okhttp3.OkHttpClient

@FlixclusiveProvider
class FlixHQ : ProviderPlugin() {
    private val client by lazy { OkHttpClient() }

    // `manifest` is injected after construction, so this must stay lazy.
    private val api by lazy { FlixHQApi(client = client, providerId = id) }

    override suspend fun getSearchApi(context: Context): SearchProviderApi = api

    override suspend fun getMetadataApi(context: Context): MediaMetadataProviderApi = api

    override suspend fun getCrossMatchApi(context: Context): CrossMatchProviderApi = api

    override suspend fun getMediaLinkApi(context: Context): MediaLinkProviderApi =
        FlixHQMediaLinkApi(context = context, api = api)
}
