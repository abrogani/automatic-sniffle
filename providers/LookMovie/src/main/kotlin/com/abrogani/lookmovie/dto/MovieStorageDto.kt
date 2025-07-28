package com.abrogani.lookmovie.dto

import com.google.gson.annotations.SerializedName

data class MovieStorageDto(
    @SerializedName("id_movie")
    val idMovie: Int,
    val type: String,
    val key: String,
    @SerializedName("movie_poster")
    val moviePoster: String,
    @SerializedName("backdrop_huge")
    val backdropHuge: String,
    @SerializedName("backdrop_medium")
    val backdropMedium: String,
    @SerializedName("backdrop_small")
    val backdropSmall: String,
    val title: String,
    val year: String,
    @SerializedName("text_tracks")
    val textTracks: List<String>,
    val hash: String,
    val expires: Long
)
