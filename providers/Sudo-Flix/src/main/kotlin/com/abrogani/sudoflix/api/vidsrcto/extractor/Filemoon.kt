package com.abrogani.sudoflix.api.vidsrcto.extractor

import com.flixclusive.model.provider.link.MediaLink
import com.abrogani.sudoflix.api.util.EmbedExtractor
import com.abrogani.sudoflix.api.util.ExtractorHelper.unpackLink
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient

internal class Filemoon(
    client: OkHttpClient
) : EmbedExtractor(client) {
    override val baseUrl = "https://kerapoxy.cc"
    override val name = "Filemoon"

    private val linkRegex = Regex("""file:"(.*?)"""")

    override suspend fun extract(
        url: String,
        customHeaders: Map<String, String>?,
        onLinkFound: (MediaLink) -> Unit
    ) {
        unpackLink(
            client = client,
            url = url,
            headers = customHeaders?.toHeaders(),
            linkRegex = linkRegex
        )
    }
}