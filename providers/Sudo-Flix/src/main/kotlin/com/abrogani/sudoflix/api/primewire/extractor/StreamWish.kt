package com.abrogani.sudoflix.api.primewire.extractor

import com.flixclusive.model.provider.link.MediaLink
import com.abrogani.sudoflix.api.util.EmbedExtractor
import com.abrogani.sudoflix.api.util.ExtractorHelper.unpackLink
import okhttp3.OkHttpClient

internal class StreamWish(
    client: OkHttpClient
) : EmbedExtractor(client) {
    override val baseUrl = "https://streamwish.to"
    override val name = "StreamWish"

    override suspend fun extract(
        url: String,
        customHeaders: Map<String, String>?,
        onLinkFound: (MediaLink) -> Unit
    ) {
        val stream = unpackLink(
            client = client,
            url = url
        )

        onLinkFound(stream)
    }
}