package com.abrogani.streamplay.util

import com.flixclusive.core.util.exception.safeCall
import com.flixclusive.model.film.FilmMetadata
import com.flixclusive.model.film.TvShow
import com.flixclusive.model.film.common.tv.Episode
import com.flixclusive.model.provider.link.Flag
import com.flixclusive.model.provider.link.Stream
import com.flixclusive.model.provider.link.Subtitle
import com.google.gson.Gson
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.phisher98.StreamPlay
import java.text.SimpleDateFormat
import java.util.Locale

internal object Mapper {
    private val gson = Gson()
    private const val CHINESE_LANGUAGE_CODE = "zh"
    private const val JAPANESE_LANGUAGE_CODE = "ja"
    private const val HINDI_LANGUAGE_CODE = "hi"
    private const val KOREAN_LANGUAGE_CODE = "ko"

    fun SubtitleFile.toSubtitle(): Subtitle {
        return Subtitle(
            language = lang,
            url = url
        )
    }

    fun ExtractorLink.toStream(): Stream {
        return Stream(
            name = """
                Source: $source
                Name: $name
            """.trimIndent(),
            url = url,
            flags = setOf(
                Flag.RequiresAuth(getAllHeaders())
            )
        )
    }

    fun FilmMetadata.toLinkDataString(episode: Episode? = null): String {
        val hasAnimationGenre = genres.any { it.id == 16 }
        val isChinese = language?.contains(CHINESE_LANGUAGE_CODE) == true
        val isJapanese = language?.contains(JAPANESE_LANGUAGE_CODE) == true
        val isKorean = language?.contains(KOREAN_LANGUAGE_CODE) == true
        val isHindi = language?.contains(HINDI_LANGUAGE_CODE) == true

        val reformattedReleaseDate = reformatDate(releaseDate)
        val episodeReleaseDate = reformatDate(episode?.releaseDate)
        val lastSeason = if (episode != null) (this as TvShow).totalSeasons else null

        val linkData = StreamPlay.LinkData(
            id = tmdbId,
            title = title,
            year = year,
            season = episode?.season,
            episode = episode?.number,
            imdbId = imdbId,
            isAnime = (isChinese || isJapanese) && hasAnimationGenre,
            isCartoon = hasAnimationGenre,
            isBollywood = isHindi,
            isAsian = isChinese || isJapanese || isKorean,
            epsTitle = episode?.title,
            date = reformattedReleaseDate,
            airedDate = episodeReleaseDate,
            airedYear = episodeReleaseDate.split("-").first().toIntOrNull(),
            lastSeason = lastSeason,
        )

        return gson.toJson(linkData)
    }

    private fun reformatDate(dateString: String?, locale: Locale = Locale.US): String {
        if (dateString == null) return ""

        val input = SimpleDateFormat("MMMM d, yyyy", locale)
        val output = SimpleDateFormat("yyyy-MM-dd", locale)

        return safeCall {
            val date = input.parse(dateString)
            output.format(date!!)
        } ?: dateString
    }
}