package com.abrogani.sudoflix.api.util

import com.flixclusive.core.util.exception.safeCall
import com.flixclusive.core.util.network.okhttp.request
import com.flixclusive.model.provider.link.Stream
import com.abrogani.sudoflix.api.util.EmbedExtractor
import okhttp3.Headers
import okhttp3.OkHttpClient

internal object ExtractorHelper {
    internal fun EmbedExtractor.getRedirectedUrl(
        client: OkHttpClient,
        url: String,
        domainName: String
    ): String {
        return client.request(url = url)
            .execute()
            .use {
                val redirectedUrl = it.request.url
                if (!it.isSuccessful && !redirectedUrl.host.contains(domainName)) {
                    throw Exception("[$name]> Failed to get redirect URL")
                }

                it.close()
                redirectedUrl.toString()
            }
    }

    private val defaultPackedRegex = Regex("""eval\((function\(p,a,c,k,e,d\)\{.*)\)""")
    private val defaultLinkRegex = Regex("""file:"(https://[^"]+)"""")

    fun EmbedExtractor.unpackLink(
        client: OkHttpClient,
        url: String,
        headers: Headers? = null,
        packedRegex: Regex = defaultPackedRegex,
        linkRegex: Regex = defaultLinkRegex
    ): Stream {
        val response = client.request(
            url = url,
            headers = headers ?: Headers.headersOf()
        ).execute()

        val streamPage = response.body?.string()
            ?: throw Exception("[$name]> Failed to load page")

        val unpackedScript = safeCall {
            val packed = packedRegex.find(streamPage)
                ?.groupValues?.get(1)

            JsUnpacker(packed).unpack()
        } ?: streamPage

        val link = linkRegex.find(unpackedScript)
            ?.groupValues?.get(1)
            ?: throw Exception("[$name]> Failed to find link")

        return Stream(
            url = link,
            name = name
        )
    }
}
