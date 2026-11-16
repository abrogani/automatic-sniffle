package com.abrogani.flixhq.extractors

import com.abrogani.flixhq.dto.ServerDto
import com.abrogani.flixhq.dto.SubtitleTrackDto
import com.abrogani.flixhq.extensions.fetchText
import com.abrogani.flixhq.extensions.parseJson
import com.abrogani.flixhq.extensions.toList
import com.flixclusive.model.provider.link.Subtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

internal class SubtitleResolver(
    private val client: OkHttpClient,
) {
    fun urlOf(server: ServerDto): String? {
        val link = server.link?.toHttpUrlOrNull() ?: return null
        return SUBTITLE_PARAMETERS.firstNotNullOfOrNull { link.queryParameter(it) }
    }

    suspend fun load(url: String): List<Subtitle> {
        val json = withContext(Dispatchers.IO) { client.fetchText(url) }
        return parse(json)
    }

    companion object {
        private val SUBTITLE_PARAMETERS = listOf("sub.info", "subget")

        fun parse(json: String): List<Subtitle> =
            parseJson(json).toList<SubtitleTrackDto>().mapNotNull { track ->
                val file = track.file?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                if (track.kind != null && track.kind != "captions") return@mapNotNull null

                Subtitle(
                    language = track.label?.takeIf { it.isNotBlank() } ?: "Unknown",
                    url = file,
                )
            }
    }
}
