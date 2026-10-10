package com.abrogani.sudoflix.api.primewire.extractor

import com.flixclusive.model.provider.link.MediaLink
import com.abrogani.sudoflix.api.util.EmbedExtractor
import com.abrogani.sudoflix.api.util.ExtractorHelper.getRedirectedUrl
import com.abrogani.sudoflix.api.util.ExtractorHelper.unpackLink
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

internal class MixDrop(
    client: OkHttpClient
) : EmbedExtractor(client) {
    override val baseUrl = "https://mixdrop.ag"
    override val name = "MixDrop"

    override suspend fun extract(
        url: String,
        customHeaders: Map<String, String>?,
        onLinkFound: (MediaLink) -> Unit
    ) {
        val cleanedUrl = getRedirectedUrl(
            client = client,
            url = url,
            domainName = "mixdrop"
        ).toHttpUrl()
        val probableHost = cleanedUrl.host
        val embedId = cleanedUrl.pathSegments
            .lastOrNull()
            ?: throw Exception("[$name]> Failed to find embed id")

        val stream = unpackLink(
            client = client,
            url = "https://$probableHost/e/$embedId",
            headers = Headers.headersOf(
                "User-Agent", "PostmanRuntime/7.41.2"
            ),
            linkRegex = Regex("""MDCore\.wurl="(.*?)";"""),
        ).let {
            when {
                it.url.startsWith("http") -> it
                else -> it.copy(url = "https:${it.url}")
            }
        }

        onLinkFound(stream)
    }
}