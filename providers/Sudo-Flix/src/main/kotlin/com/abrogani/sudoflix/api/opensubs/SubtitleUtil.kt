package com.abrogani.sudoflix.api.opensubs

import com.flixclusive.core.util.exception.safeCall
import com.abrogani.sudoflix.api.util.fromJson
import com.flixclusive.core.util.network.okhttp.request
import com.flixclusive.model.provider.link.Subtitle
import com.flixclusive.model.provider.link.SubtitleSource
import okhttp3.OkHttpClient

internal object SubtitleUtil {
    private const val OPEN_SUBS_STREMIO_ENDPOINT = "https://opensubtitles-v3.strem.io"

    private data class OpenSubtitleStremioDto(
        val subtitles: List<Map<String, String>>,
    )

    fun OkHttpClient.fetchSubtitles(
        imdbId: String,
        season: Int? = null,
        episode: Int? = null,
        onSubtitleLoaded: (Subtitle) -> Unit
    ) {
        val slug = if(season == null) {
            "movie/$imdbId"
        } else {
            "series/$imdbId:$season:$episode"
        }

        val subtitles = request("$OPEN_SUBS_STREMIO_ENDPOINT/subtitles/$slug.json")
            .execute()
            .use {
                val string = it.body?.string()

                if (!it.isSuccessful || string == null) {
                    return
                }

                safeCall {
                    fromJson<OpenSubtitleStremioDto>(string)
                }
            }

        subtitles?.subtitles?.forEach { subtitle ->
            val subLanguage = subtitle["lang"] ?: return@forEach
            val subtitleDto = Subtitle(
                url = subtitle["url"] ?: return@forEach,
                language = "[OpenSubs] $subLanguage",
                type = SubtitleSource.ONLINE
            )

            onSubtitleLoaded(subtitleDto)
        }
    }
}