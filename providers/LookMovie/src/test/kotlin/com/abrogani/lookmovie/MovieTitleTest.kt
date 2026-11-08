package com.abrogani.lookmovie

import com.abrogani.lookmovie.extensions.parseMovieTitle
import org.jsoup.Jsoup
import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isNull

class MovieTitleTest {
    @Test
    fun `multi-word title keeps every word and drops the year`() {
        val doc = Jsoup.parse("""<h1 class="bd-hd">The Odyssey <span>2026</span></h1>""")
        expectThat(doc.parseMovieTitle()).isEqualTo("The Odyssey")
    }

    @Test
    fun `missing heading returns null`() {
        expectThat(Jsoup.parse("<div></div>").parseMovieTitle()).isNull()
    }
}
