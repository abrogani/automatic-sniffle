package com.abrogani.flixhq

import android.content.Context
import com.abrogani.flixhq.api.FlixHQCatalogApi
import com.abrogani.flixhq.api.FlixHQCrossMatchApi
import com.abrogani.flixhq.api.FlixHQMediaLinkApi
import com.abrogani.flixhq.api.FlixHQMetadataApi
import com.abrogani.flixhq.api.FlixHQSearchApi
import com.flixclusive.provider.FlixclusiveProvider
import com.flixclusive.provider.ProviderPlugin
import com.flixclusive.provider.capability.CatalogProviderApi
import com.flixclusive.provider.capability.CrossMatchProviderApi
import com.flixclusive.provider.capability.MediaLinkProviderApi
import com.flixclusive.provider.capability.MediaMetadataProviderApi
import com.flixclusive.provider.capability.SearchProviderApi
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@FlixclusiveProvider
class FlixHQ : ProviderPlugin() {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val catalogApi by lazy { FlixHQCatalogApi(client = client, providerId = id) }
    private val searchApi by lazy { FlixHQSearchApi(client = client, providerId = id) }
    private val metadataApi by lazy { FlixHQMetadataApi(client = client, providerId = id) }
    private val crossMatchApi by lazy { FlixHQCrossMatchApi(searchApi = searchApi, metadataApi = metadataApi) }

    @Volatile
    private var mediaLinkApi: FlixHQMediaLinkApi? = null

    override suspend fun getCatalogApi(context: Context): CatalogProviderApi = catalogApi

    override suspend fun getSearchApi(context: Context): SearchProviderApi = searchApi

    override suspend fun getMetadataApi(context: Context): MediaMetadataProviderApi = metadataApi

    override suspend fun getCrossMatchApi(context: Context): CrossMatchProviderApi = crossMatchApi

    override suspend fun getMediaLinkApi(context: Context): MediaLinkProviderApi =
        mediaLinkApi ?: synchronized(this) {
            mediaLinkApi ?: FlixHQMediaLinkApi(context = context, client = client).also { mediaLinkApi = it }
        }

    override suspend fun onUnload(context: Context) {
        mediaLinkApi?.destroy()
        mediaLinkApi = null
    }
}
