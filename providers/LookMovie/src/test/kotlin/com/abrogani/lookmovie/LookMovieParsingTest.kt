package com.abrogani.lookmovie

import com.abrogani.lookmovie.dto.ShowStorageDto
import com.abrogani.lookmovie.extensions.extractStorageObject
import com.abrogani.lookmovie.extensions.parseStorage
import com.abrogani.lookmovie.extensions.toShowPartialMedia
import com.abrogani.lookmovie.extensions.toSlidePartialMedia
import com.flixclusive.model.media.common.MediaType
import org.jsoup.Jsoup
import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isGreaterThan
import strikt.assertions.isNotEmpty

/** Offline tests against saved LookMovie markup. */
class LookMovieParsingTest {
    private fun fixture(name: String) =
        checkNotNull(javaClass.getResource("/fixtures/$name")) { "Missing fixture: $name" }.readText()

    @Test
    fun `show_storage with nested objects is parsed`() {
        val storage = parseStorage<ShowStorageDto>(fixture("show_storage.js"), "show_storage")!!

        expectThat(storage.idShow).isEqualTo(44920)
        expectThat(storage.title).isEqualTo("Dexter: Resurrection")
        expectThat(storage.hash).isEqualTo("sKA1uez9lyTRafEulyfCBg")
        expectThat(storage.seasons.orEmpty()).hasSize(13)

        val first = storage.seasons!!.filterNotNull().first()
        expectThat(first.idEpisode).isEqualTo(330339L)
        expectThat(first.season).isEqualTo("1")
        expectThat(first.episode).isEqualTo("1")
    }

    @Test
    fun `trailing comma in seasons does not fail parsing`() {
        val html = "window['show_storage'] = { id_show: 1, hash: 'h', expires: 2, seasons: [ " +
            "{ title: 'A', episode: '1', id_episode: 3, season: '1' }, ] };"

        val storage = parseStorage<ShowStorageDto>(html, "show_storage")!!

        expectThat(storage.seasons.orEmpty().filterNotNull()).hasSize(1)
    }

    @Test
    fun `missing storage returns null`() {
        expectThat(extractStorageObject("<html>captcha</html>", "show_storage")).isEqualTo(null)
    }

    @Test
    fun `braces and escaped quotes inside strings do not break extraction`() {
        val html = "window['movie_storage'] = { title: 'It\\'s a {tricky} one', hash: 'x' }; other()"

        expectThat(extractStorageObject(html, "movie_storage"))
            .isEqualTo("{ title: 'It\\'s a {tricky} one', hash: 'x' }")
    }

    @Test
    fun `shows listing is deduplicated into shows without query params in ids`() {
        val elements = Jsoup.parse(fixture("shows_list.html")).select("div.tv-episodes-list div.episode-item")
        val shows = elements
            .map { it.toShowPartialMedia(baseUrl = "https://x.test", providerId = "p") }
            .distinctBy { it.id }

        expectThat(elements.size).isGreaterThan(0)
        expectThat(shows.map { it.id }.any { '?' in it || '/' in it }).isEqualTo(false)
        expectThat(shows.first().type).isEqualTo(MediaType.SHOW)
        expectThat(shows.first().title).isNotEmpty()
        expectThat(shows.first().homePage!!.startsWith("https://x.test/shows/view/")).isEqualTo(true)
    }

    @Test
    fun `slider items are parsed`() {
        val slides = Jsoup.parse(fixture("home_slider.html"))
            .select("div.sliderPresentation div.slide-item")
            .map { it.toSlidePartialMedia(baseUrl = "https://x.test", providerId = "p") }

        expectThat(slides).hasSize(3)
        expectThat(slides.first().id).isEqualTo("37803364-possible-love-2026")
        expectThat(slides.first().title).isEqualTo("Possible Love")
        expectThat(slides.first().type).isEqualTo(MediaType.MOVIE)
        expectThat(slides.first().rating).isEqualTo(8.1)
    }
}
