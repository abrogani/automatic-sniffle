package com.abrogani.flixhq

import com.abrogani.flixhq.extensions.episodeId
import com.abrogani.flixhq.extensions.toMediaId
import com.abrogani.flixhq.extensions.toPageUrl
import com.abrogani.flixhq.parser.FlixHQParser
import com.flixclusive.model.media.common.MediaType
import org.jsoup.Jsoup
import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isNotNull
import strikt.assertions.isNull
import strikt.assertions.isTrue

class FlixHQParserTest {
    private fun fixture(name: String) =
        Jsoup.parse(checkNotNull(javaClass.getResource("/fixtures/$name")) { "Missing fixture: $name" }.readText())

    @Test
    fun `listing items map type id title poster and year`() {
        val page = FlixHQParser.parseListing(fixture("movies_listing.html"), "p", page = 1)

        expectThat(page.results).hasSize(3)
        val first = page.results.first()
        expectThat(first.type).isEqualTo(MediaType.MOVIE)
        expectThat(first.id).isEqualTo("watch-movie/kiku-chiranjeevi-2026-watch-online")
        expectThat(first.title).isEqualTo("Kiku Chiranjeevi")
        expectThat(first.providerId).isEqualTo("p")
        expectThat(first.posterImage).isEqualTo("https://qqqcdn.cloud/images/posters/uAlTYjwLAX9VCP8mSbKDiKzkqcS.webp")
        expectThat(first.releaseDate).isNotNull()
        expectThat(first.homePage).isEqualTo("https://flixhqz.net/watch-movie/kiku-chiranjeevi-2026-watch-online/")
    }

    @Test
    fun `listing pagination reads next page and last page`() {
        val page = FlixHQParser.parseListing(fixture("movies_listing.html"), "p", page = 1)

        expectThat(page.hasNextPage).isTrue()
        expectThat(page.totalPages).isEqualTo(1002)
    }

    @Test
    fun `listing without pager has no next page`() {
        val page = FlixHQParser.parseListing(fixture("series_listing.html"), "p", page = 1)

        expectThat(page.hasNextPage).isFalse()
        expectThat(page.totalPages).isEqualTo(1)
    }

    @Test
    fun `series listing items are shows`() {
        val page = FlixHQParser.parseListing(fixture("series_listing.html"), "p")

        expectThat(page.results).hasSize(2)
        expectThat(page.results.first().type).isEqualTo(MediaType.SHOW)
        expectThat(page.results.first().id).isEqualTo("watch-series/the-mob-2026-watch-online")
        expectThat(page.results.first().releaseDate).isNotNull()
    }

    @Test
    fun `genres come from the sidebar`() {
        val genres = FlixHQParser.parseGenres(fixture("home_genres.html"))

        expectThat(genres).contains("Drama" to "genre/drama")
        expectThat(genres.map { it.second }.distinct()).hasSize(genres.size)
    }

    @Test
    fun `movie detail is parsed`() {
        val detail = FlixHQParser.parseDetail(fixture("movie_detail.html"), "p")

        expectThat(detail.title).isEqualTo("Other Mommy")
        expectThat(detail.overview).isNotNull().contains("Young Bela")
        expectThat(detail.rating).isEqualTo(5.3)
        expectThat(detail.runtime).isEqualTo(93)
        expectThat(detail.certification).isEqualTo("R")
        expectThat(detail.language).isEqualTo("en")
        expectThat(detail.posterImage).isEqualTo("https://qqqcdn.cloud/images/posters/fGRNCssTRrhYZxNzykGGCoMsAVq.webp")
        expectThat(detail.backdropImage).isEqualTo("https://qqqcdn.cloud/images/backdrops/ncm7djsVTHQQMX2HEbEVxMUFchG.webp")
        expectThat(detail.genres.map { it.name }).isEqualTo(listOf("Horror"))
        expectThat(detail.casts.map { it.name }.first()).isEqualTo("Jessica Chastain")
        expectThat(detail.casts).hasSize(7)
        expectThat(detail.producers.map { it.name }).isEqualTo(listOf("Spin a Black Yarn"))
        expectThat(detail.releaseDate).isNotNull()
        expectThat(detail.imdbId).isNull()
    }

    @Test
    fun `series detail exposes imdb id`() {
        val detail = FlixHQParser.parseDetail(fixture("series_detail.html"), "p")

        expectThat(detail.title).isEqualTo("The Mob")
        expectThat(detail.imdbId).isEqualTo("tt42007258")
        expectThat(detail.rating).isEqualTo(3.8)
    }

    @Test
    fun `series seasons hold every episode`() {
        val seasons = FlixHQParser.parseSeasons(fixture("series_detail.html"))

        expectThat(seasons).hasSize(1)
        val season = seasons.single()
        expectThat(season.number).isEqualTo(1)
        expectThat(season.episodes).hasSize(8)
        expectThat(season.episodes.first().id).isEqualTo("episode/the-mob-2026-watch-online/s01-e01")
        expectThat(season.episodes.first().number).isEqualTo(1)
        expectThat(season.episodes[4].title).isEqualTo("Turns Out, I Am That Bitch")
    }

    @Test
    fun `movie page token uses players and episode page token uses players_show`() {
        val movie = FlixHQParser.parsePlayerToken(fixture("movie_detail.html"))
        val episode = FlixHQParser.parsePlayerToken(fixture("episode_page.html"))

        expectThat(movie).isNotNull().get { key }.isEqualTo("players")
        expectThat(movie!!.value.length).isEqualTo(236)
        expectThat(episode).isNotNull().get { key }.isEqualTo("players_show")
    }

    @Test
    fun `ids and urls round trip`() {
        expectThat("https://flixhqz.net/watch-movie/a-2026-watch-online/".toMediaId())
            .isEqualTo("watch-movie/a-2026-watch-online")
        expectThat("/watch-series/b/".toMediaId()).isEqualTo("watch-series/b")
        expectThat("watch-movie/a".toPageUrl()).isEqualTo("https://flixhqz.net/watch-movie/a/")
        expectThat(episodeId("watch-series/the-mob-2026-watch-online", 1, 2))
            .isEqualTo("episode/the-mob-2026-watch-online/s01-e02")
        expectThat(FlixHQParser.isEpisodeId("episode/x/s01-e02")).isTrue()
        expectThat(FlixHQParser.isEpisodeId("watch-series/x")).isFalse()
    }
}
