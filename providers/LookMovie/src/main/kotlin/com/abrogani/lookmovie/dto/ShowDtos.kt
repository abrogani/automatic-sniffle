package com.abrogani.lookmovie.dto

import com.google.gson.annotations.SerializedName

/**
 * The `window['show_storage']` object found in `/shows/play/{slug}` and `/shows/view/{slug}`.
 *
 * Gson bypasses Kotlin defaults, so anything the site may omit is nullable.
 * */
internal data class ShowStorageDto(
    @SerializedName("id_show")
    val idShow: Int,
    val title: String?,
    val year: String?,
    @SerializedName("poster_medium")
    val posterMedium: String?,
    @SerializedName("backdrop_huge")
    val backdropHuge: String?,
    @SerializedName("total_episodes")
    val totalEpisodes: Int?,
    val hash: String?,
    val expires: Long?,
    /**
     * Despite the name, this is a flat list of episodes of every season.
     * The page may end it with a trailing comma, which lenient Gson reads as a null element.
     * */
    val seasons: List<ShowStorageEpisodeDto?>?,
)

internal data class ShowStorageEpisodeDto(
    val title: String?,
    val episode: String?,
    val season: String?,
    @SerializedName("id_episode")
    val idEpisode: Long?,
)

/** Response of `/api/v2/download/episode/list?id={showId}`: season -> episode -> id. */
internal data class EpisodeListResponseDto(
    val list: Map<String, Map<String, EpisodeIdDto>>?,
)

internal data class EpisodeIdDto(
    @SerializedName("id_episode")
    val idEpisode: String,
)

/** Response of the `do-search` endpoints of both movies and shows. */
internal data class SearchResponseDto(
    val result: List<SearchItemDto>?,
)

internal data class SearchItemDto(
    val slug: String,
    val title: String,
    val description: String?,
    val year: Int?,
    @SerializedName("imdb_rating")
    val imdbRating: String?,
    val poster: Map<String, String>?,
)
