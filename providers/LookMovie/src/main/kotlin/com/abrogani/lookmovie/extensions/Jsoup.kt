package com.abrogani.lookmovie.extensions

import com.flixclusive.model.media.PartialMedia
import com.flixclusive.model.media.common.MediaType
import org.jsoup.nodes.Element

internal fun Element.toPartialMedia(
    baseUrl: String,
    providerId: String,
    type: MediaType
): PartialMedia {
    val hrefLink = select("div.hvr-inner a").attr("href").takeIf { it.isNotEmpty() }
        ?: select("a").first()?.attr("href") ?: ""

    val posterUrl = select("div.image__placeholder a img").attr("data-src").takeIf { it.isNotEmpty() }
        ?: select("p.item__image").attr("data-background-image")

    return PartialMedia(
        id = hrefLink.toMediaId(),
        title = select("div.mv-item-infor h6 a").text().trim(),
        posterImage = posterUrl.toAbsoluteUrl(baseUrl),
        rating = select("p.rate span").text().toDoubleOrNull(),
        releaseDate = select("p.year").text().toIntOrNull()?.toReleaseDate(),
        type = type,
        providerId = providerId,
        homePage = hrefLink.toAbsoluteUrl(baseUrl),
    )
}

/**
 * The `/shows` listing is made of *episodes*, not shows, so the link carries query params
 * (`?season=4&episode=18&id_episode=...`) that must not leak into the id.
 * */
internal fun Element.toShowPartialMedia(
    baseUrl: String,
    providerId: String,
): PartialMedia {
    val hrefLink = select("a").first()?.attr("href").orEmpty().substringBefore("?")

    return PartialMedia(
        id = hrefLink.toMediaId(),
        title = select("div.mv-item-infor h6").text().trim(),
        posterImage = select("div.image__placeholder img").attr("data-src").toAbsoluteUrl(baseUrl),
        overview = select("p.describe").text().trim().takeIf { it.isNotEmpty() },
        type = MediaType.SHOW,
        providerId = providerId,
        homePage = hrefLink.toAbsoluteUrl(baseUrl),
    )
}

/** An item of the `sliderPresentation` carousel in the home page. Can be a movie or a show. */
internal fun Element.toSlidePartialMedia(
    baseUrl: String,
    providerId: String,
): PartialMedia {
    val hrefLink = select("div.slide-item__backdrop--wrapper a").first()?.attr("href").orEmpty()

    val title = select("h2.slide-item__title")
    val year = title.select("span").text().trim('(', ')', ' ').toIntOrNull()
    val image = select("img.owl-lazy").first()

    return PartialMedia(
        id = hrefLink.toMediaId(),
        title = title.first()?.ownText()?.trim().orEmpty(),
        posterImage = image?.attr("data-src-portrait").toAbsoluteUrl(baseUrl),
        backdropImage = image?.attr("data-src-landscape").toAbsoluteUrl(baseUrl),
        rating = select("div.slide-item__stat span").first()?.text()?.toDoubleOrNull(),
        releaseDate = year?.toReleaseDate(),
        overview = select("div.slide-item__description").text().trim().takeIf { it.isNotEmpty() },
        type = if (hrefLink.startsWith("/shows/")) MediaType.SHOW else MediaType.MOVIE,
        providerId = providerId,
        homePage = hrefLink.toAbsoluteUrl(baseUrl),
    )
}
