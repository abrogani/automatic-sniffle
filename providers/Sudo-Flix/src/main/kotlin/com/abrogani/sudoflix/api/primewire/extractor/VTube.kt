package com.abrogani.sudoflix.api.primewire.extractor

import com.flixclusive.model.provider.link.MediaLink
import com.abrogani.sudoflix.api.util.EmbedExtractor
import com.abrogani.sudoflix.api.util.ExtractorHelper.getRedirectedUrl
import com.abrogani.sudoflix.api.util.ExtractorHelper.unpackLink
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

internal class VTube(
    client: OkHttpClient
) : EmbedExtractor(client) {
    override val baseUrl = "https://vtube.network"
    override val name = "VTube"

    override suspend fun extract(
        url: String,
        customHeaders: Map<String, String>?,
        onLinkFound: (MediaLink) -> Unit
    ) {
        val newUrl = baseUrl.toHttpUrl()
        val safeUrl = getRedirectedUrl(
            client = client,
            url = url,
            domainName = "vtube"
        ).toHttpUrl()
            .newBuilder()
            .scheme(newUrl.scheme)
            .host(newUrl.host)
            .build()
            .toString()

        val stream = unpackLink(
            client = client,
            url = safeUrl
        )

        onLinkFound(stream)
    }
}