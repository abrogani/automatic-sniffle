package com.abrogani.superstream

import android.content.Context
import androidx.compose.runtime.Composable
import com.abrogani.superstream.api.SuperStreamApi
import com.abrogani.superstream.settings.GetTokenScreen
import com.flixclusive.provider.FlixclusiveProvider
import com.flixclusive.provider.ProviderPlugin
import com.flixclusive.provider.capability.CrossMatchProviderApi
import com.flixclusive.provider.capability.MediaLinkProviderApi
import com.flixclusive.provider.capability.MediaMetadataProviderApi
import com.flixclusive.provider.capability.SearchProviderApi
import okhttp3.OkHttpClient

@FlixclusiveProvider
class SuperStream : ProviderPlugin() {
    private val client by lazy { OkHttpClient() }

    // `manifest`, `settings` and `resources` are injected after construction, so this must stay lazy.
    private val api by lazy {
        SuperStreamApi(
            client = client,
            providerId = id,
            resources = resources!!,
            settings = settings
        )
    }

    @Composable
    override fun SettingsScreen() {
        GetTokenScreen(settings = settings)
    }

    override suspend fun getSearchApi(context: Context): SearchProviderApi = api

    override suspend fun getMetadataApi(context: Context): MediaMetadataProviderApi = api

    override suspend fun getCrossMatchApi(context: Context): CrossMatchProviderApi = api

    override suspend fun getMediaLinkApi(context: Context): MediaLinkProviderApi = api
}
