package com.abrogani.superstream.dto

import com.flixclusive.model.media.PartialMedia
import com.abrogani.superstream.util.SuperStreamUtil
import com.google.gson.annotations.SerializedName

internal data class SearchData(
    @SerializedName("data") val results: List<SearchItem>,
    val total: Int = 0,
)

internal data class SearchItem(
    val id: Int? = null,
    @SerializedName("box_type") val boxType: Int? = null,
    val title: String? = null,
    val year: Int? = null,
) {
    companion object {
        fun SearchItem.toPartialMedia(): PartialMedia {
            return PartialMedia(
                id = id.toString(),
                title = title ?: "UNKNOWN TITLE",
                type = SuperStreamUtil.SSMediaType.getSSMediaType(boxType).toMediaType(),
                posterImage = null,
                homePage = null,
                providerId = "SuperStream"
            )
        }
    }
}
