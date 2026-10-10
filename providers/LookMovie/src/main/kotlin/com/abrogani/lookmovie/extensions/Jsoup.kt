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

    val id = hrefLink.substringAfterLast("/")

    val title = select("div.mv-item-infor h6 a").text().trim()

    val posterUrl = select("div.image__placeholder a img").attr("data-src").takeIf { it.isNotEmpty() }
        ?: select("p.item__image").attr("data-background-image")

    val rating = select("p.rate span").text().toDoubleOrNull()
    val year = select("p.year").text().toIntOrNull()

    return PartialMedia(
        id = id,
        title = title,
        posterImage = if (posterUrl.isNotEmpty()) baseUrl + posterUrl else null,
        rating = rating,
        releaseDate = year?.toReleaseDate(),
        type = type,
        providerId = providerId,
        homePage = baseUrl + hrefLink,
    )
}
