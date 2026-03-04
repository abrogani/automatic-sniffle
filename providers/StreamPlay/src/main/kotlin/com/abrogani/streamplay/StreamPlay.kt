package com.abrogani.streamplay

import android.content.Context
import com.flixclusive.provider.FlixclusiveProvider
import com.flixclusive.provider.Provider
import com.phisher98.StreamPlayPlugin
import okhttp3.OkHttpClient

@FlixclusiveProvider
class StreamPlay : Provider() {
    private val cs3Plugin by lazy { StreamPlayPlugin() }
    private var isInitialized = false

    override fun getApi(
        context: Context,
        client: OkHttpClient,
    ): StreamPlayApi {
        if (!isInitialized) {
            cs3Plugin.load(context)
            isInitialized = true
        }

        return StreamPlayApi(
            client = client,
            provider = this,
        )
    }
}