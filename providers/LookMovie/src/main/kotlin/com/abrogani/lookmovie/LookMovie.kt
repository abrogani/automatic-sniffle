package com.abrogani.lookmovie

import android.content.Context
import com.abrogani.lookmovie.api.LookMovieCatalogApi
import com.abrogani.lookmovie.api.LookMovieMetadataApi
import com.abrogani.lookmovie.api.LookMovieSearchApi
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

    // `manifest` is injected after construction, so these must stay lazy.
    private val catalogApi by lazy { LookMovieCatalogApi(client = client, providerId = id) }
    private val searchApi by lazy { LookMovieSearchApi(client = client, providerId = id) }
    private val metadataApi by lazy { LookMovieMetadataApi(client = client, providerId = id) }

    private var mediaLinkApi: LookMovieMediaLinkApi? = null

    override suspend fun getCatalogApi(context: Context): CatalogProviderApi = catalogApi

    override suspend fun getSearchApi(context: Context): SearchProviderApi = searchApi

    override suspend fun getMetadataApi(context: Context): MediaMetadataProviderApi = metadataApi

    override suspend fun getMediaLinkApi(context: Context): MediaLinkProviderApi =
        mediaLinkApi ?: LookMovieMediaLinkApi(
            context = context,
            client = client,
            metadataApi = metadataApi,
        ).also { mediaLinkApi = it }

    override suspend fun onUnload(context: Context) {
        mediaLinkApi?.destroy()
        mediaLinkApi = null
    }
}
