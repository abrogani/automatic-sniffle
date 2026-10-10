package com.abrogani.sudoflix.api.ridomovies.extractor

import com.abrogani.sudoflix.api.ridomovies.RidoMoviesConstant.RIDO_MOVIES_BASE_URL
import com.abrogani.sudoflix.api.util.JsUnpacker
import com.flixclusive.core.util.coroutines.mapAsync
import com.flixclusive.core.util.network.Crypto
import com.flixclusive.core.util.network.jsoup.asJsoup
import com.flixclusive.core.util.network.okhttp.request
import com.flixclusive.model.provider.link.Flag
import com.flixclusive.model.provider.link.MediaLink
import com.flixclusive.model.provider.link.Stream
import com.flixclusive.model.provider.link.Subtitle
import com.flixclusive.model.provider.link.SubtitleSource
import com.abrogani.sudoflix.api.util.EmbedExtractor
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient

internal class CloseLoad(
    client: OkHttpClient
) : EmbedExtractor(client) {
    override val baseUrl = "https://closeload.top"
    override val name = "CloseLoad"

    private val headers = mapOf(
        "Referer" to "$RIDO_MOVIES_BASE_URL/"
    ).toHeaders()

    override suspend fun extract(
        url: String,
        customHeaders: Map<String, String>?,
        onLinkFound: (MediaLink) -> Unit
    ) {
        val links = mutableListOf<MediaLink>()

        val response = client.request(
            url = url,
            headers = headers
        ).execute()

        if (!response.isSuccessful) {
            throw IllegalStateException("Failed to fetch page [$name]")
        }

        val htmlCode = response.asJsoup()

        val captions = htmlCode.select("track")

        captions.mapAsync {
            links.add(
                Subtitle(
                    language = "[$name] ${it.attr(" label ")}",
                    url = baseUrl+it.attr("src"),
                    type = SubtitleSource.ONLINE
                )
            )
        }

        val packedScript = htmlCode.selectFirst("script:containsData(function(p,a,c,k,e,d))")
            ?.data()
            ?: throw IllegalStateException("[$name]> Could not find eval script")

        val unpacked = JsUnpacker(packedScript).unpack()
            ?: throw NullPointerException("[$name]> Unable to find source url")

        val regexPattern = """var\s+(\w+)\s*=\s*"([^"]+)";""".toRegex()
        val matchResult = regexPattern.find(unpacked)
        val base64EncodedUrl = matchResult?.groups?.get(2)?.value
            ?: throw NullPointerException("[$name]> Unable to find source url")

        val sourceUrl = Crypto.base64Decode(base64EncodedUrl)

        onLinkFound(
            Stream(
                url = sourceUrl,
                name = "[$name]> HLS",
                flags = setOf(
                    Flag.RequiresAuth(
                        customHeaders = mapOf("Referer" to "$baseUrl/")
                    )
                )
            )
        )
    }
}