package com.abrogani.sudoflix.api.primewire.extractor

import com.flixclusive.model.provider.link.MediaLink
import com.abrogani.sudoflix.api.util.EmbedExtractor
import com.abrogani.sudoflix.api.util.ExtractorHelper.getRedirectedUrl
import com.abrogani.sudoflix.api.util.ExtractorHelper.unpackLink
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import java.net.URL

internal class UpStream(
    client: OkHttpClient
) : EmbedExtractor(client) {
    override val baseUrl = "https://upstream.to"
    override val name = "Upstream"

    override suspend fun extract(
        url: String,
        customHeaders: Map<String, String>?,
        onLinkFound: (MediaLink) -> Unit
    ) {
        val embedUrl = getEmbedUrl(url = URL(url))

        val stream = unpackLink(
            client = client,
            url = embedUrl,
            headers = mapOf(
                "Referer" to "https://primewire.tf/",
            ).toHeaders(),
            linkRegex = Regex("""sources:\[\{file:"(.*?)"""")
        )

        onLinkFound(stream)
    }

    private fun getEmbedUrl(url: URL): String {
        return when {
            !url.host.contains("upstream", true) -> {
                val redirectedUrl = getRedirectedUrl(
                    client = client,
                    url = url.toString(),
                    domainName = "upstream"
                )
                getEmbedUrl(URL(redirectedUrl))
            }
            !url.path.contains("embed", true) -> {
                val embedId = url.path.split("/").last()

                "https://${url.host}/embed-$embedId.html"
            }
            else -> url.toString()
        }
    }
}