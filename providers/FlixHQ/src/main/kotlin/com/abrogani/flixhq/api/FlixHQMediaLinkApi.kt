package com.abrogani.flixhq.api

import android.content.Context
import com.abrogani.flixhq.dto.PlayerToken
import com.abrogani.flixhq.dto.ServerDto
import com.abrogani.flixhq.extensions.episodeId
import com.abrogani.flixhq.extensions.fetchDocument
import com.abrogani.flixhq.extensions.parseJson
import com.abrogani.flixhq.extensions.toList
import com.abrogani.flixhq.extensions.toMediaId
import com.abrogani.flixhq.extensions.toPageUrl
import com.abrogani.flixhq.extractors.ByseExtractor
import com.abrogani.flixhq.extractors.EmbedExtractor
import com.abrogani.flixhq.extractors.SubtitleResolver
import com.abrogani.flixhq.extractors.VidmolyExtractor
import com.abrogani.flixhq.parser.FlixHQParser
import com.flixclusive.core.util.log.errorLog
import com.flixclusive.core.util.network.okhttp.HttpMethod
import com.flixclusive.core.util.network.okhttp.request
import com.flixclusive.model.media.MediaMetadata
import com.flixclusive.model.media.common.tv.Episode
import com.flixclusive.model.provider.link.MediaLink
import com.flixclusive.model.provider.link.Stream
import com.flixclusive.provider.capability.MediaLinkProviderApi
import com.flixclusive.provider.capability.MediaLinkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

internal class FlixHQMediaLinkApi(
    context: Context,
    private val client: OkHttpClient,
) : MediaLinkProviderApi {
    private val byse = ByseExtractor(context)
    private val extractors: List<EmbedExtractor> = listOf(VidmolyExtractor(client), byse)
    private val subtitles = SubtitleResolver(client)

    override val supportedLinkTypes = setOf(MediaLinkType.STREAMS, MediaLinkType.SUBTITLES)

    override suspend fun getLinks(
        media: MediaMetadata,
        episode: Episode?,
        onLinkFound: (MediaLink) -> Unit,
    ) {
        val pageId = pageId(media, episode)
        val servers = withContext(Dispatchers.IO) { fetchServers(pageId) }
        if (servers.isEmpty()) throw IOException("No servers available for $pageId")

        val seen = ConcurrentHashMap.newKeySet<String>()
        val streams = AtomicInteger()
        val emit: (MediaLink) -> Unit = { link ->
            if (seen.add(link.url)) {
                if (link is Stream) streams.incrementAndGet()
                onLinkFound(link)
            }
        }

        coroutineScope {
            servers.firstNotNullOfOrNull { subtitles.urlOf(it) }?.let { url ->
                launch { attempt("subtitles") { subtitles.load(url).forEach(emit) } }
            }

            servers.forEach { server ->
                val extractor = extractors.firstOrNull { it.matches(server) } ?: return@forEach
                launch { attempt(extractor.name) { extractor.extract(server, emit) } }
            }
        }

        if (streams.get() == 0) throw IOException("No playable streams found for $pageId")
    }

    suspend fun destroy() = byse.destroy()

    private fun pageId(media: MediaMetadata, episode: Episode?): String {
        if (!media.isShow) return media.id.toMediaId()

        val target = episode ?: throw IllegalArgumentException("Episode is required for TV shows")
        return target.id.takeIf(FlixHQParser::isEpisodeId)?.toMediaId()
            ?: episodeId(media.id, target.season, target.number)
    }

    private fun fetchServers(pageId: String): List<ServerDto> {
        val pageUrl = pageId.toPageUrl()
        val token = FlixHQParser.parsePlayerToken(client.fetchDocument(pageUrl))
            ?: throw IllegalStateException("Player token not found on $pageUrl")

        return requestServers(token, pageUrl)
    }

    private fun requestServers(token: PlayerToken, referer: String): List<ServerDto> {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(token.key, token.value)
            .build()

        val headers = Headers.headersOf("Referer", referer, "X-Requested-With", "XMLHttpRequest")

        return client.request(
            url = AJAX_URL,
            method = HttpMethod.POST,
            body = body,
            headers = headers,
            userAgent = USER_AGENT,
        ).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $AJAX_URL")
            parseJson(response.body.string()).toList<ServerDto>()
        }.filter { !it.link.isNullOrBlank() }
    }

    private suspend fun attempt(label: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorLog("FlixHQ $label failed: ${e.message}")
        }
    }
}
