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
        private val LANGUAGE_CODE = Regex("""\s*\[[A-Za-z]{2,3}]""")
        private val TRAILING_INDEX = Regex("""\s+\d+$""")

        fun cleanLabel(label: String): String =
            label.replace(LANGUAGE_CODE, "").replace(TRAILING_INDEX, "").trim().trimEnd('-').trim()

        fun parse(json: String): List<Subtitle> =
            disambiguate(parseJson(json).toList<SubtitleTrackDto>().mapNotNull { track ->
                val file = track.file?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                if (track.kind != null && track.kind != "captions") return@mapNotNull null

                Subtitle(
                    language = track.label?.takeIf { it.isNotBlank() }?.let(::cleanLabel)?.ifEmpty { null } ?: "Unknown",
                    url = file,
                )
            })

        private fun disambiguate(subtitles: List<Subtitle>): List<Subtitle> {
            val seen = HashMap<String, Int>()
            val totals = subtitles.groupingBy { it.language }.eachCount()

            return subtitles.map { subtitle ->
                if ((totals[subtitle.language] ?: 0) < 2) return@map subtitle

                val index = seen.merge(subtitle.language, 1, Int::plus)!!
                subtitle.copy(language = "${subtitle.language} $index")
            }
        }
    }
}
