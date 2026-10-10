package com.abrogani.sudoflix.api.nsbx

import com.abrogani.sudoflix.api.util.year
import com.flixclusive.model.media.MediaMetadata
import com.flixclusive.model.media.common.MediaIdSource.Companion.imdbId
import com.flixclusive.model.media.common.MediaIdSource.Companion.tmdbId
import okhttp3.OkHttpClient

internal class VidBingeApi(
    client: OkHttpClient
) : AbstractNsbxApi(
    client = client
) {
    override val baseUrl = "https://api.whvx.net"
    override val name = "VidBinge"
    override val streamSourceUrl = "$baseUrl/source"

    override fun MediaMetadata.getQuery(
        season: Int?,
        episode: Int?
    ): String {
        val filmType = if (season != null) "show" else "movie"

        var query = """
            {"title":"$title","releaseYear":${year},"tmdbId":"$tmdbId","imdbId":"$imdbId","type":"$filmType"
        """.trimIndent()

        if (season != null) {
            query += """
                ,"season":"$season","episode":"$episode"
            """.trimIndent()
        }

        return "$query}"
    }
}