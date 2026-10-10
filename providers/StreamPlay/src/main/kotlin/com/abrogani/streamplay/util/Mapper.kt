package com.abrogani.streamplay.util

import com.flixclusive.model.media.MediaMetadata
import com.flixclusive.model.media.Show
import com.flixclusive.model.media.common.MediaIdSource.Companion.imdbId
import com.flixclusive.model.media.common.MediaIdSource.Companion.tmdbId
import com.flixclusive.model.media.common.tv.Episode
import com.flixclusive.model.provider.link.Flag
import com.flixclusive.model.provider.link.Stream
import com.flixclusive.model.provider.link.Subtitle
import com.google.gson.Gson
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.phisher98.StreamPlay
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal object Mapper {
    private val gson = Gson()
    private const val CHINESE_LANGUAGE_CODE = "zh"
    private const val JAPANESE_LANGUAGE_CODE = "ja"
    private const val HINDI_LANGUAGE_CODE = "hi"
    private const val KOREAN_LANGUAGE_CODE = "ko"
    private const val ANIMATION_GENRE = "Animation"

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

    fun MediaMetadata.toLinkDataString(episode: Episode? = null): String {
        val hasAnimationGenre = genres.any { it.name.equals(ANIMATION_GENRE, ignoreCase = true) }
        val isChinese = language?.contains(CHINESE_LANGUAGE_CODE) == true
        val isJapanese = language?.contains(JAPANESE_LANGUAGE_CODE) == true
        val isKorean = language?.contains(KOREAN_LANGUAGE_CODE) == true
        val isHindi = language?.contains(HINDI_LANGUAGE_CODE) == true

        val reformattedReleaseDate = reformatDate(releaseDate)
        val episodeReleaseDate = reformatDate(episode?.releaseDate)
        val lastSeason = if (episode != null) (this as? Show)?.totalSeasons else null

        val linkData = StreamPlay.LinkData(
            id = tmdbId?.toIntOrNull(),
            title = title,
            year = releaseDate?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).year },
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

    private fun reformatDate(epochMillis: Long?): String {
        if (epochMillis == null) return ""

        return DateTimeFormatter.ISO_LOCAL_DATE.format(
            Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC)
        )
    }
}
