package com.abrogani.superstream.api.dto.old

import com.abrogani.superstream.api.util.old.SuperStreamUtil.toReleaseDate
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

    fun toMovie(providerId: String) = Movie(
        id = id.toString(),
        title = title
            ?: throw NullPointerException("Movie title should not be blank or null!"),
        releaseDate = released.toReleaseDate(),
        posterImage = null,
        homePage = null,
        providerId = providerId
    )

    fun toShow(providerId: String) = Show(
        id = id.toString(),
        title = title
            ?: throw NullPointerException("Show title should not be blank or null!"),
        releaseDate = released.toReleaseDate(),
        seasons = emptyList(),
        totalSeasons = maxSeason ?: 0,
        totalEpisodes = maxEpisode ?: 0,
        posterImage = null,
        homePage = null,
        providerId = providerId
    )
}
