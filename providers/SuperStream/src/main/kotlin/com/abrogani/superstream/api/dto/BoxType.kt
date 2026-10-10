package com.abrogani.superstream.api.dto

import com.flixclusive.model.media.common.MediaType

enum class BoxType(val value: Int) {
    Series(value = 2),
    Movies(value = 1);

    companion object {
        fun fromMediaType(type: MediaType): BoxType {
            return when (type) {
                MediaType.SHOW -> Series
                MediaType.MOVIE -> Movies
            }
        }
        fun toMediaType(type: Int): MediaType {
            return when (type) {
                1 -> MediaType.MOVIE
                2 -> MediaType.SHOW
                else -> throw IllegalArgumentException("Invalid box type: $type")
            }
        }
    }
}
