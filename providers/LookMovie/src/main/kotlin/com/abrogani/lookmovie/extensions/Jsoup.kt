package com.abrogani.lookmovie.extensions

import com.flixclusive.model.film.FilmSearchItem
import com.flixclusive.model.film.util.FilmType
import org.jsoup.nodes.Element

internal fun Element.toFilmSearchItem(
    baseUrl: String,
    providerId: String,
    filmType: FilmType
): FilmSearchItem {
    val hrefLink = select("div.hvr-inner a").attr("href").takeIf { it.isNotEmpty() }
        ?: select("a").first()?.attr("href") ?: ""

    val id = hrefLink.substringAfterLast("/")

    val title = select("div.mv-item-infor h6 a").text().trim()

    val posterUrl = select("div.image__placeholder a img").attr("data-src").takeIf { it.isNotEmpty() }
        ?: select("p.item__image").attr("data-background-image")

    val rating = select("p.rate span").text().toDoubleOrNull() ?: 0.0
    val year = select("p.year").text().toIntOrNull() ?: 0

    return FilmSearchItem(
        id = id,
        title = title,
        posterImage = if (posterUrl.isNotEmpty()) baseUrl + posterUrl else null,
        rating = rating,
        year = year,
        filmType = filmType,
        providerId = providerId,
        homePage = baseUrl + hrefLink,
    )
}