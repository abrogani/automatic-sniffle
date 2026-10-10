package com.abrogani.flixhq.api.util

import com.flixclusive.model.media.PartialMedia
import com.flixclusive.model.media.common.MediaType
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

internal fun Elements.getEpisodeId(episode: Int): String? {
    return try {
        var episodeId: String? = null

        val episodeDoc = select("a.eps-item:contains(Eps $episode)")

        if (episodeDoc.isNotEmpty()) {
            episodeId = episodeDoc.attr("data-id")
        }

        return episodeId
    } catch (e: Exception) {
        null
    }
}

internal fun Elements.getSeasonId(season: Int): String? {
    return try {
        var seasonId: String? = null

        if (size == 1 && season == 1 || this[season - 1].text().contains("Season $season")) {
            return this[season - 1].attr("data-id")
        }

        val seasonDoc = select("a:contains(Season ${season})")

        if (seasonDoc.isNotEmpty()) {
            seasonId = seasonDoc.attr("data-id")
        }

        return seasonId
    } catch (e: Exception) {
        null
    }
}

internal fun String.replaceWhitespaces(toReplace: String) = replace(
    Regex("[\\s_]+"),
    toReplace
)

internal fun Element.toPartialMedia(
    baseUrl: String,
    providerId: String
): PartialMedia {
    val type = when {
        select("div.film-detail > div.fd-infor > span.float-right").text() == "Movie" -> MediaType.MOVIE
        else -> MediaType.SHOW
    }

    val year = if (type == MediaType.MOVIE) {
        val rawYear = select("div.film-detail > div.fd-infor > span:nth-child(1)").text()

        rawYear.toIntOrNull()
    } else null


    return PartialMedia(
        id = select("div.film-poster > a").attr("href").substring(1),
        providerId = providerId,
        title = select("div.film-detail > h2 > a").attr("title"),
        posterImage = select("div.film-poster > img").attr("data-src"),
        homePage = "${baseUrl}${select("div.film-poster > a").attr("href")}",
        releaseDate = year?.let { LocalDate.of(it, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() },
        type = type
    )
}

/** Parses an ISO date (`yyyy-MM-dd`) into epoch millis, or `null` if it is not one. */
internal fun String.toReleaseDate(): Long? = try {
    LocalDate.parse(this).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
} catch (e: Exception) {
    null
}

internal fun Long.year(): Int = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).year

internal fun Elements.getServerName(mediaId: String): String {
    val anchorElement = select("a")
    val titleElement = anchorElement.attr("title")

    return if (mediaId.contains("movie")) {
        titleElement
            .lowercase(Locale.US)
    } else {
        titleElement.substring(6)
            .trim()
            .lowercase(Locale.US)
    }
}

internal fun Elements.getServerUrl(
    baseUrl: String,
    mediaId: String
): String {
    return "${baseUrl}/${mediaId}.${
        if (!mediaId.contains("movie")) {
            attr("data-id")
        } else {
            attr("data-linkid")
        }
    }".replace(
        if (!mediaId.contains("movie")) {
            Regex("/tv/")
        } else {
            Regex("/movie/")
        },
        if (!mediaId.contains("movie")) {
            "/watch-tv/"
        } else {
            "/watch-movie/"
        }
    )
}