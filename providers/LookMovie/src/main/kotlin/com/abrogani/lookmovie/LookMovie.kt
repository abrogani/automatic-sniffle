package com.abrogani.lookmovie

import android.content.Context
import com.abrogani.lookmovie.api.LookMovieApi
import com.abrogani.lookmovie.api.LookMovieMediaLinkApi
import com.flixclusive.provider.FlixclusiveProvider
import com.flixclusive.provider.ProviderPlugin
import com.flixclusive.provider.capability.CatalogProviderApi
import com.flixclusive.provider.capability.MediaLinkProviderApi
import com.flixclusive.provider.capability.MediaMetadataProviderApi
import com.flixclusive.provider.capability.SearchProviderApi
import okhttp3.OkHttpClient

@FlixclusiveProvider
class LookMovie : ProviderPlugin() {
    private val client by lazy { OkHttpClient() }

    // `manifest` is injected after construction, so this must stay lazy.
    private val api by lazy { LookMovieApi(client = client, providerId = id) }

    private var mediaLinkApi: LookMovieMediaLinkApi? = null

    override suspend fun getCatalogApi(context: Context): CatalogProviderApi = api

    override suspend fun getSearchApi(context: Context): SearchProviderApi = api

    override suspend fun getMetadataApi(context: Context): MediaMetadataProviderApi = api

    override suspend fun getMediaLinkApi(context: Context): MediaLinkProviderApi =
        mediaLinkApi ?: LookMovieMediaLinkApi(
            context = context,
            client = client,
            metadataApi = api,
        ).also { mediaLinkApi = it }

    override suspend fun onUnload(context: Context) {
        mediaLinkApi?.destroy()
        mediaLinkApi = null
    }
}
