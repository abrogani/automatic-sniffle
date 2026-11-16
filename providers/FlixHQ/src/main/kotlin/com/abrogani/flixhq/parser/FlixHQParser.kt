package com.abrogani.flixhq.parser

import com.abrogani.flixhq.api.EPISODE_PREFIX
import com.abrogani.flixhq.api.SERIES_PREFIX
import com.abrogani.flixhq.dto.PlayerToken
import com.abrogani.flixhq.extensions.parseJson
import com.abrogani.flixhq.extensions.toAbsoluteUrl
import com.abrogani.flixhq.extensions.toMediaId
import com.abrogani.flixhq.extensions.toPageUrl
import com.abrogani.flixhq.extensions.toReleaseDate
import com.abrogani.flixhq.extensions.toReleaseDateOrNull
import com.flixclusive.model.media.PartialMedia
import com.flixclusive.model.media.common.Cast
import com.flixclusive.model.media.common.Company
import com.flixclusive.model.media.common.Genre
import com.flixclusive.model.media.common.MediaType
import com.flixclusive.model.media.common.PaginatedMedia
import com.flixclusive.model.media.common.tv.Episode
import com.flixclusive.model.media.common.tv.Season
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal data class DetailPage(
    val title: String,
    val overview: String?,
    val posterImage: String?,
    val backdropImage: String?,
    val rating: Double?,
    val runtime: Int?,
    val certification: String?,
    val language: String?,
    val releaseDate: Long?,
    val imdbId: String?,
    val genres: List<Genre>,
    val casts: List<Cast>,
    val producers: List<Company>,
    val recommendations: List<PartialMedia>,
)

internal object FlixHQParser {
    private val YEAR = Regex("""\d{4}""")
    private val SLUG_YEAR = Regex("""-(\d{4})-watch-online""")
    private val PAGE_NUMBER = Regex("""(?:/page/|[?&]page=)(\d+)""")
    private val EPISODE_PATH = Regex("""s(\d+)-e(\d+)$""")
    private val MINUTES = Regex("""(\d+)\s*min""")
    private val IMDB = Regex("""imdb\.com/title/(tt\d+)""")
    private val BACKGROUND = Regex("""url\(['"]?([^'")]+)['"]?\)""")

    fun parseListing(document: Document, providerId: String, page: Int = 1): PaginatedMedia<PartialMedia> {
        val results = document.select("div.flw-item").mapNotNull { it.toPartialMedia(providerId) }

        val pages = document.select("a.page-link[href]")
            .mapNotNull { PAGE_NUMBER.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
        val hasNextPage = document.selectFirst("a.page-link[rel=next]") != null

        return PaginatedMedia(
            page = page,
            results = results,
            hasNextPage = hasNextPage,
            totalPages = maxOf(pages.maxOrNull() ?: page, page),
        )
    }

    fun parseRecommendations(document: Document, providerId: String): List<PartialMedia> =
        document.select(".film-related div.flw-item").mapNotNull { it.toPartialMedia(providerId) }

    fun parseGenres(document: Document): List<Pair<String, String>> =
        document.select("#sidebar_subs_genre a[href^=/genre/]")
            .map { it.text().trim() to it.attr("href").toMediaId() }
            .filter { (name, slug) -> name.isNotEmpty() && slug.isNotEmpty() }
            .distinctBy { it.second }

    fun parseDetail(document: Document, providerId: String): DetailPage {
        val ld = parseStructuredData(document)
        val rows = document.parseRows()

        val title = document.selectFirst("h1.heading-name")?.text()?.trim().orEmpty()
        require(title.isNotEmpty()) { "Title not found on the detail page" }

        val rating = document.select(".stats .item")
            .firstOrNull { it.selectFirst("i.fa-star") != null }
            ?.text()?.trim()?.toDoubleOrNull()
            ?: ld?.get("aggregateRating")?.asJsonObjectOrNull()?.get("ratingValue")?.asDoubleOrNull()

        val runtime = document.select(".stats .item").firstNotNullOfOrNull {
            MINUTES.find(it.text())?.groupValues?.get(1)?.toIntOrNull()
        } ?: ld?.get("duration")?.asStringOrNull()?.let { Regex("""PT(\d+)M""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        return DetailPage(
            title = title,
            overview = ld?.get("description")?.asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?: document.selectFirst(".m_i-d-content .description")?.descriptionText(),
            posterImage = document.selectFirst("img#image_storage")?.attr("src").toAbsoluteUrl()
                ?: ld?.get("image")?.asStringOrNull().toAbsoluteUrl(),
            backdropImage = document.selectFirst(".w_b-cover")?.attr("style")
                ?.let { BACKGROUND.find(it)?.groupValues?.get(1) }.toAbsoluteUrl(),
            rating = rating?.takeIf { it > 0 },
            runtime = runtime,
            certification = ld?.get("contentRating")?.asStringOrNull(),
            language = ld?.get("inLanguage")?.asStringOrNull(),
            releaseDate = rows["released"]?.ownText()?.toReleaseDateOrNull(),
            imdbId = ld?.get("sameAs")?.toString()?.let { IMDB.find(it)?.groupValues?.get(1) },
            genres = rows["genre"]?.select("a")?.map { Genre(name = it.text().trim()) }.orEmpty(),
            casts = rows["casts"]?.select("a")?.map { Cast(name = it.text().trim()) }.orEmpty(),
            producers = rows["production"]?.select("a")?.map { Company(name = it.text().trim()) }.orEmpty(),
            recommendations = parseRecommendations(document, providerId),
        )
    }

    fun parseSeasons(document: Document): List<Season.Full> =
        document.select("div.ss-episodes").mapNotNull { pane ->
            val number = pane.id().substringAfterLast('-').toIntOrNull() ?: return@mapNotNull null

            val episodes = pane.select("a.eps-item").mapNotNull { link ->
                val id = link.attr("href").toMediaId()
                val match = EPISODE_PATH.find(id) ?: return@mapNotNull null
                val episodeNumber = match.groupValues[2].toInt()

                Episode(
                    id = id,
                    number = episodeNumber,
                    season = number,
                    isReleased = true,
                    title = link.attr("title").substringAfter(": ", "").trim().takeIf { it.isNotEmpty() },
                )
            }.sortedBy { it.number }

            if (episodes.isEmpty()) return@mapNotNull null

            Season.Full(
                id = "season-$number",
                number = number,
                isReleased = true,
                title = "Season $number",
                episodes = episodes,
            )
        }.sortedBy { it.number }

    fun parsePlayerToken(document: Document): PlayerToken? {
        document.selectFirst("#series-player[data-token]")?.let {
            return PlayerToken(key = "players_show", value = it.attr("data-token"))
        }
        document.selectFirst("#main-wrapper[data-token]")?.let {
            return PlayerToken(key = "players", value = it.attr("data-token"))
        }
        return null
    }

    fun isEpisodeId(id: String): Boolean = id.toMediaId().startsWith(EPISODE_PREFIX)

    private fun Element.toPartialMedia(providerId: String): PartialMedia? {
        val id = selectFirst("a.film-poster-ahref")?.attr("href").orEmpty().toMediaId()
        if (id.isEmpty()) return null

        val title = selectFirst("h3.film-name a")?.text()?.trim().orEmpty()
        if (title.isEmpty()) return null

        val year = select(".fd-infor .fdi-item")
            .firstNotNullOfOrNull { YEAR.matchEntire(it.text().trim())?.value?.toIntOrNull() }
            ?: SLUG_YEAR.find(id)?.groupValues?.get(1)?.toIntOrNull()

        return PartialMedia(
            type = if (id.startsWith(SERIES_PREFIX)) MediaType.SHOW else MediaType.MOVIE,
            id = id,
            title = title,
            providerId = providerId,
            posterImage = selectFirst("img.film-poster-img")?.attr("data-src").toAbsoluteUrl(),
            releaseDate = year?.toReleaseDate(),
            homePage = id.toPageUrl(),
        )
    }

    private fun Document.parseRows(): Map<String, Element> =
        select(".elements .row-line").mapNotNull { row ->
            val label = row.selectFirst("span.type")?.text()?.trim()?.removeSuffix(":")?.lowercase()
                ?: return@mapNotNull null
            label to row
        }.toMap()

    private fun Element.descriptionText(): String? {
        val copy = clone()
        copy.select("strong").remove()
        return copy.text().trim().removePrefix("-").trim().takeIf { it.isNotEmpty() }
    }

    private fun parseStructuredData(document: Document): com.google.gson.JsonObject? =
        document.select("script[type=application/ld+json]").firstNotNullOfOrNull { script ->
            runCatching { parseJson(script.data()) }.getOrNull()
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.takeIf { it.get("@type")?.asStringOrNull() in setOf("Movie", "TVSeries") }
        }

    private fun com.google.gson.JsonElement.asStringOrNull(): String? =
        takeIf { it.isJsonPrimitive }?.asString

    private fun com.google.gson.JsonElement.asDoubleOrNull(): Double? =
        takeIf { it.isJsonPrimitive }?.runCatching { asDouble }?.getOrNull()

    private fun com.google.gson.JsonElement.asJsonObjectOrNull(): com.google.gson.JsonObject? =
        takeIf { it.isJsonObject }?.asJsonObject
}
