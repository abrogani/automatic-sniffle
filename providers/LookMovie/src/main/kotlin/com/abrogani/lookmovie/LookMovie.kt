package com.abrogani.lookmovie

import android.content.Context
import com.abrogani.lookmovie.api.LookMovieApi
import com.abrogani.lookmovie.api.webview.LookMovieWebViewApi
import com.flixclusive.provider.FlixclusiveProvider
import com.flixclusive.provider.Provider
import com.flixclusive.provider.ProviderApi
import okhttp3.OkHttpClient

@FlixclusiveProvider
class LookMovie : Provider() {
    override fun getApi(
        context: Context,
        client: OkHttpClient
    ): ProviderApi {
        val baseApi = LookMovieApi(
            client = client,
            provider = this
        )

        return LookMovieWebViewApi(
            baseApi = baseApi,
            context = context
        )
    }
}
