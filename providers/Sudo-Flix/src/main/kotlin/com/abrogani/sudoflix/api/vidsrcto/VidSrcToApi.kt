package com.abrogani.sudoflix.api.vidsrcto

import com.flixclusive.core.util.coroutines.asyncCalls
import com.flixclusive.core.util.network.jsoup.asJsoup
import com.abrogani.sudoflix.api.util.fromJson
import com.flixclusive.core.util.network.okhttp.request
import com.flixclusive.model.provider.link.MediaLink
import com.flixclusive.model.provider.link.Subtitle
import com.flixclusive.model.media.MediaMetadata
import com.flixclusive.model.media.common.MediaIdSource.Companion.imdbId
import com.flixclusive.model.media.common.MediaIdSource.Companion.tmdbId
import com.flixclusive.model.media.common.MediaType
import com.flixclusive.model.media.common.tv.Episode
import com.flixclusive.provider.capability.MediaLinkProviderApi
import com.flixclusive.provider.capability.MediaLinkType
import com.abrogani.sudoflix.api.util.EmbedExtractor
import com.abrogani.sudoflix.api.vidsrcto.dto.EmbedSourcesResponse
import com.abrogani.sudoflix.api.vidsrcto.dto.SourcesListResponse
import com.abrogani.sudoflix.api.vidsrcto.extractor.Filemoon
import com.abrogani.sudoflix.api.vidsrcto.util.VidSrcToDecryptionUtil.decodeUrl
import com.abrogani.sudoflix.api.util.urlType
import okhttp3.OkHttpClient

internal const val VIDSRCTO_KEY = "WXrUARXb1aDLaZjI"

internal class VidSrcToApi(
    private val client: OkHttpClient
) : MediaLinkProviderApi {
    override val supportedLinkTypes = setOf(MediaLinkType.STREAMS, MediaLinkType.SUBTITLES)

    private val name = "VidSrc.To"
    private val baseUrl = "https://vidsrc.to"

    private val extractors = mapOf(
        "filemoon" to Filemoon(client),
    )

    override suspend fun getLinks(
        media: MediaMetadata,
        episode: Episode?,
        onLinkFound: (MediaLink) -> Unit
    ) {
        val actualWatchId = media.imdbId ?: media.tmdbId ?: media.id
        val episodeSlug = if (episode != null) "/${episode.season}/${episode.number}" else ""

        val dataId = client.request(
            url = "$baseUrl/embed/${media.urlType}/$actualWatchId$episodeSlug"
        ).execute()
            .asJsoup()
            .selectFirst("ul.episodes li a")
            ?.attr("data-id")
            ?: throw IllegalStateException("[$name]> Can't find data-id")

        val baseFilmUrl = "$baseUrl/ajax/embed/episode/$dataId"
        val sources = client.request(
            url = "$baseFilmUrl/sources"
        ).execute()
            .fromJson<SourcesListResponse>("[$name]> Can't parse sources")

        if (sources.status != 200 || sources.result.isEmpty()) {
            throw IllegalStateException("[$name]> Can't find sources")
        }

        asyncCalls(
            {
                sources.result.forEach {
                    val extractor = extractors[it["title"]?.lowercase()]
                    if (extractor != null) {
                        val id = it["id"]
                            ?: throw IllegalStateException("[$name]> Can't find extractor id")

                        extractor.extractFromSources(
                            id = id,
                            onLinkFound = onLinkFound
                        )
                    }
                }
            },
            {
                getSubtitles(
                    url = "$baseFilmUrl/subtitles",
                    onSubtitleLoaded = onLinkFound
                )
            },
        )
    }

    private suspend fun EmbedExtractor.extractFromSources(
        id: String,
        onLinkFound: (MediaLink) -> Unit
    ) {
        val response = client.request(
            url = "${this@VidSrcToApi.baseUrl}/ajax/embed/source/$id"
        ).execute()

        val embedData = response.fromJson<EmbedSourcesResponse>("[$name]> Can't parse embed data")

        if (embedData.status != 200) {
            throw IllegalStateException("[$name]> Error fetching embed sources")
        }

        val encryptedUrl = embedData.result["url"]
            ?: throw IllegalStateException("[$name]> Can't find encrypted url")
        val decryptedUrl = decodeUrl(encryptedUrl)

        return extract(
            url = decryptedUrl,
            onLinkFound = onLinkFound
        )
    }

    private fun getSubtitles(
        url: String,
        onSubtitleLoaded: (Subtitle) -> Unit
    ) {
        val subtitles = client.request(url = url).execute()
            .fromJson<List<Map<String, String>>>()

        subtitles.forEach {
            val file = it["file"]
            val label = it["label"]

            if (file != null) {
                onSubtitleLoaded(
                    Subtitle(
                        url = file,
                        language = "[$name] ${label ?: " UNKNOWN LANG"}"
                    )
                )
            }
        }
    }
}