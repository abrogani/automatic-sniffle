package com.abrogani.sudoflix.api.ridomovies.extractor

import com.flixclusive.core.util.network.okhttp.request
import com.flixclusive.model.provider.link.MediaLink
import com.flixclusive.model.provider.link.Stream
import com.abrogani.sudoflix.api.util.EmbedExtractor
import com.abrogani.sudoflix.api.ridomovies.RidoMoviesConstant.RIDO_MOVIES_BASE_URL
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient

internal class Ridoo(
    client: OkHttpClient
) : EmbedExtractor(client) {
    override val baseUrl: String
        get() = "https://ridoo.net"
    override val name: String
        get() = "Ridoo"

    override suspend fun extract(
        url: String,
        customHeaders: Map<String, String>?,
        onLinkFound: (MediaLink) -> Unit
    ) {
        val response = client.request(
            url = url,
            headers = mapOf(
                "Referer" to "$RIDO_MOVIES_BASE_URL/"
            ).toHeaders()
        ).execute()

        if (!response.isSuccessful || response.body == null) {
            throw IllegalStateException("[$name]> Failed to fetch page")
        }

        val responseString = response.body!!.string()

        val regexPattern = """file:"([^"]+)"""".toRegex()
        val matchResult = regexPattern.find(responseString)
        val sourceUrl = matchResult?.groups?.get(1)?.value
            ?: throw Exception("[$name]> Unable to find source url")

        onLinkFound(
            Stream(
                url = sourceUrl,
                name = "[$name]> HLS Auto"
            )
        )
    }
}