package com.abrogani.sudoflix.api.primewire.extractor

import com.flixclusive.core.util.exception.safeCall
import com.flixclusive.core.util.network.Crypto
import com.flixclusive.core.util.network.okhttp.request
import com.flixclusive.model.provider.link.MediaLink
import com.flixclusive.model.provider.link.Stream
import com.abrogani.sudoflix.api.util.EmbedExtractor
import okhttp3.OkHttpClient

internal class Voe(
    client: OkHttpClient
) : EmbedExtractor(client) {
    override val baseUrl = "https://voe.sx"
    override val name = "Voe"

    private val linkRegex = Regex("""'hls': ?'(http.*?)',""")

    override suspend fun extract(
        url: String,
        customHeaders: Map<String, String>?,
        onLinkFound: (MediaLink) -> Unit
    ) {
        val streamPage = client.request(url = url)
            .execute().body?.string()
            ?: throw Exception("[$name]> Failed to load page")

        val playerSrcMatch = linkRegex.find(streamPage)

        var streamUrl = playerSrcMatch?.groupValues?.get(1)
            ?: throw Exception("[$name]> Stream URL not found in embed code")

        safeCall {
            if (!streamUrl.startsWith("http"))
                streamUrl = Crypto.base64Decode(streamUrl)
        }

        return onLinkFound(
            Stream(
                url = streamUrl,
                name = name
            )
        )
    }
}