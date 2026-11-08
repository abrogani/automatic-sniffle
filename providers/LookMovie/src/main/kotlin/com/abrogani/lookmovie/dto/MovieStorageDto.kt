package com.abrogani.lookmovie.dto

import com.google.gson.annotations.SerializedName

internal data class MovieStorageDto(
    @SerializedName("id_movie")
    val idMovie: Int,
    val hash: String,
    val expires: Long
)
