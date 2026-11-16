package com.abrogani.flixhq.api

import com.flixclusive.model.media.MediaMetadata
import com.flixclusive.model.media.PartialMedia
import com.flixclusive.model.media.common.MediaIdSource
import com.flixclusive.model.media.common.MediaType
import com.abrogani.flixhq.extensions.toYear
import com.flixclusive.provider.capability.CrossMatchProviderApi
import com.flixclusive.provider.filter.FilterList

internal class FlixHQCrossMatchApi(
    private val searchApi: FlixHQSearchApi,
    private val metadataApi: FlixHQMetadataApi,
) : CrossMatchProviderApi {
    override suspend fun getById(
        mediaType: MediaType,
        sourceIds: Map<MediaIdSource, String>,
    ): MediaMetadata? {
        val imdbId = sourceIds[MediaIdSource.IMDB]?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val candidate = searchApi.search(imdbId, 1, FilterList()).results
            .firstOrNull { it.type == mediaType }
            ?: return null

        val metadata = resolve(candidate)
        val matchedId = metadata.externalIds[MediaIdSource.IMDB]
        return metadata.takeIf { matchedId == null || matchedId == imdbId }
    }

    override suspend fun getByFuzzy(media: MediaMetadata): MediaMetadata? {
        val title = media.title.normalized()
        if (title.isEmpty()) return null

        val year = media.releaseDate?.toYear()

        val candidates = searchApi.search(media.title, 1, FilterList()).results
            .filter { it.type == media.type && it.title.normalized() == title }

        val matches = if (year != null) {
            candidates.filter { candidate -> candidate.releaseDate?.toYear().let { it == null || it == year } }
        } else {
            candidates
        }

        val pick = matches.singleOrNull()
            ?: matches.firstOrNull { year != null && it.releaseDate?.toYear() == year }
            ?: return null

        return resolve(pick)
    }

    private suspend fun resolve(media: PartialMedia): MediaMetadata =
        if (media.type == MediaType.SHOW) metadataApi.getShow(media) else metadataApi.getMovie(media)

    private fun String.normalized(): String =
        lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
}
