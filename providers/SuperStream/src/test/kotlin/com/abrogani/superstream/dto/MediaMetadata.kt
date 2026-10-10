package com.abrogani.superstream.dto

import com.abrogani.superstream.util.Constants.PROVIDER_TAG
import com.flixclusive.model.media.Movie
import com.flixclusive.model.media.Show
import com.google.gson.annotations.SerializedName

internal data class MediaMetadata(
    val id: Int? = null,
    val title: String? = null,
    val year: Int? = null,
    val released: String? = null,
    @SerializedName("max_season") val maxSeason: Int? = null,
    @SerializedName("max_episode") val maxEpisode: Int? = null,
) {
    val isMovie: Boolean
        get() = maxSeason == null && maxEpisode == null

    fun toMovie() = Movie(
        id = id.toString(),
        title = title
            ?: throw NullPointerException("Movie title should not be blank or null!"),
        posterImage = null,
        homePage = null,
        providerId = PROVIDER_TAG
    )

    fun toShow() = Show(
        id = id.toString(),
        title = title
            ?: throw NullPointerException("Show title should not be blank or null!"),
        seasons = emptyList(),
        totalSeasons = maxSeason ?: 0,
        totalEpisodes = maxEpisode ?: 0,
        posterImage = null,
        homePage = null,
        providerId = PROVIDER_TAG
    )
}
