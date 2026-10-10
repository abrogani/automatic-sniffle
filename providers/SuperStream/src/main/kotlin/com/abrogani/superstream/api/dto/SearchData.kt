package com.abrogani.superstream.api.dto

import com.abrogani.superstream.BuildConfig.SUPERSTREAM_FIRST_API
import com.abrogani.superstream.api.util.old.SuperStreamUtil.yearToReleaseDate
import com.flixclusive.model.media.PartialMedia
import com.flixclusive.model.media.common.MediaType
import com.google.gson.annotations.SerializedName

internal const val ITEMS_PER_PAGE = 20


internal data class SearchData(
    @SerializedName("data") val results: List<SearchItem>,
    val total: Int = 0,
)

internal data class SearchItem(
    @SerializedName("audio_lang") val language: String? = null,
    @SerializedName("imdb_rating") private val imdbRating: String? = null,
    @SerializedName("title") val name: String,
    @SerializedName("box_type") val boxType: Int,
    val id: Int,
    val poster: String? = null,
    val year: Int? = null,
) {
    private val rating: Double?
        get() = imdbRating?.toDoubleOrNull()

    private val mediaType: MediaType
        get() = when (boxType) {
            BoxType.Series.value -> MediaType.SHOW
            BoxType.Movies.value -> MediaType.MOVIE
            else -> throw IllegalArgumentException("[SuperStream]> Unknown media type: $boxType")
        }

    private val homePage: String
        get() = "$SUPERSTREAM_FIRST_API/${if (mediaType.isMovie) "movie" else "tv"}/$id"

    fun toPartialMedia(providerId: String) = PartialMedia(
        id = id.toString(),
        providerId = providerId,
        title = name,
        rating = rating,
        releaseDate = year?.yearToReleaseDate(),
        homePage = homePage,
        posterImage = poster,
        type = mediaType,
    )
}
