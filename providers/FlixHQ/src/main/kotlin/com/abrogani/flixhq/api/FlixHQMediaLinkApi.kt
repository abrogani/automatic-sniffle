package com.abrogani.flixhq.api

import android.content.Context
import com.abrogani.flixhq.webview.FlixHQWebView
import com.flixclusive.core.util.coroutines.FlxDispatchers.Companion.withMainContext
import com.flixclusive.core.util.webview.WebViewDriverManager
import com.flixclusive.model.media.MediaMetadata
import com.flixclusive.model.media.common.tv.Episode
import com.flixclusive.model.provider.link.MediaLink
import com.flixclusive.provider.capability.MediaLinkProviderApi
import com.flixclusive.provider.capability.MediaLinkType

class FlixHQMediaLinkApi(
    private val context: Context,
    private val api: FlixHQApi,
) : MediaLinkProviderApi {
    override val supportedLinkTypes = setOf(MediaLinkType.STREAMS, MediaLinkType.SUBTITLES)

    override suspend fun getLinks(
        media: MediaMetadata,
        episode: Episode?,
        onLinkFound: (MediaLink) -> Unit
    ) {
        // WebViews must be created and driven on the main thread.
        val webView = withMainContext {
            FlixHQWebView(
                mClient = api.client,
                context = context,
                api = api
            ).also { WebViewDriverManager.register(it) }
        }

        try {
            webView.getLinks(
                media = media,
                episode = episode,
                onLinkFound = onLinkFound
            )
        } finally {
            withMainContext { webView.destroy() }
        }
    }
}
