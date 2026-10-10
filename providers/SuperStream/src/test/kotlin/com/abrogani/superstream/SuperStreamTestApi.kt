package com.abrogani.superstream

import com.flixclusive.core.util.coroutines.asyncCalls
import com.flixclusive.model.media.MediaMetadata as FlxMediaMetadata
import com.flixclusive.model.media.Movie
import com.flixclusive.model.media.PartialMedia
import com.flixclusive.model.media.Show
import com.flixclusive.model.media.common.PaginatedMedia
import com.flixclusive.model.media.common.tv.Episode
import com.flixclusive.model.media.common.tv.Season
import com.flixclusive.model.provider.link.MediaLink
import com.flixclusive.model.provider.link.Stream
import com.flixclusive.model.provider.link.Subtitle
import com.flixclusive.provider.capability.MediaLinkProviderApi
import com.flixclusive.provider.capability.MediaLinkType
import com.flixclusive.provider.capability.MediaMetadataProviderApi
import com.flixclusive.provider.capability.SearchProviderApi
import com.flixclusive.provider.filter.FilterList
import com.abrogani.superstream.dto.CommonResponse
import com.abrogani.superstream.dto.MediaMetadata
import com.abrogani.superstream.dto.SearchData
import com.abrogani.superstream.dto.SearchItem.Companion.toPartialMedia
import com.abrogani.superstream.dto.StreamData
import com.abrogani.superstream.dto.SubtitleData
import com.abrogani.superstream.dto.SubtitleItem.Companion.toValidSubtitleFilePath
import com.abrogani.superstream.util.Constants.APP_ID
import com.abrogani.superstream.util.Constants.APP_VERSION
import com.abrogani.superstream.util.SuperStreamUtil.getExpiryDate
import com.abrogani.superstream.util.SuperStreamUtil.raiseOnError
import com.abrogani.superstream.util.SuperStreamUtil.superStreamCall
import okhttp3.OkHttpClient

class SuperStreamTestApi(
    private val client: OkHttpClient,
) : SearchProviderApi, MediaMetadataProviderApi, MediaLinkProviderApi {
    override val filters: FilterList = FilterList()

    override val supportedLinkTypes = setOf(MediaLinkType.STREAMS, MediaLinkType.SUBTITLES)

    override suspend fun getMovie(media: PartialMedia): Movie {
        val apiQuery =
            """{"childmode":"0","uid":"","app_version":"$APP_VERSION","appid":"$APP_ID","module":"Movie_detail","channel":"Website","mid":"${media.id}","lang":"en","expired_date":"${getExpiryDate()}","platform":"android","oss":"","group":""}"""

        return fetchMetadata(apiQuery).toMovie()
    }

    override suspend fun getShow(media: PartialMedia): Show {
        val apiQuery =
            """{"childmode":"0","uid":"","app_version":"$APP_VERSION","appid":"$APP_ID","module":"TV_detail_1","display_all":"1","channel":"Website","lang":"en","expired_date":"${getExpiryDate()}","platform":"android","tid":"${media.id}"}"""

        return fetchMetadata(apiQuery).toShow()
    }

    override suspend fun getSeason(show: Show, season: Season.Partial): Season.Full? = null

    private fun fetchMetadata(apiQuery: String): MediaMetadata {
        val response = client.superStreamCall<CommonResponse<MediaMetadata>>(apiQuery)
        response?.msg?.raiseOnError("Failed to fetch movie info.")

        return response?.data!!
    }

    override suspend fun search(
        query: String,
        page: Int,
        filters: FilterList
    ): PaginatedMedia<PartialMedia> {
        val itemsPerPage = 20
        val apiQuery =
            // Originally 8 pagelimit
            """{"childmode":"0","app_version":"$APP_VERSION","appid":"$APP_ID","module":"Search5","channel":"Website","page":"$page","lang":"en","type":"all","keyword":"$query","pagelimit":"$itemsPerPage","expired_date":"${getExpiryDate()}","platform":"android"}"""

        val response = client.superStreamCall<SearchData>(query = apiQuery)

        val mappedItems = response?.results?.map {
            it.toPartialMedia()
        } ?: throw NullPointerException("Cannot search on SuperStream")

        return PaginatedMedia(
            page = page,
            results = mappedItems,
            hasNextPage = (page * itemsPerPage) < response.total
        )
    }

    override suspend fun getLinks(
        media: FlxMediaMetadata,
        episode: Episode?,
        onLinkFound: (MediaLink) -> Unit
    ) {
        val watchId = media.id
        val season = episode?.season
        val episodeNumber = episode?.number

        val isMovie = episode == null

        val tvShowInfo = if(season != null) {
            getShow(PartialMedia(type = media.type, id = media.id, title = media.title, providerId = media.providerId, posterImage = null))
        } else null

        val seasonToUse = if(
            tvShowInfo?.totalSeasons != null
            && tvShowInfo.totalSeasons <= season!!
            && tvShowInfo.totalEpisodes >= episodeNumber!!
        ) {
            tvShowInfo.totalSeasons
        } else season

        val query = if (isMovie) {
            """{"childmode":"0","uid":"14710508","app_version":"$APP_VERSION","appid":"$APP_ID","module":"Movie_downloadurl","channel":"Website","mid":"$watchId","lang":"en","expired_date":"${getExpiryDate()}","platform":"android","oss":"1","group":""}"""
        } else {
            """{"childmode":"0","app_version":"$APP_VERSION","module":"TV_downloadurl","channel":"Website","episode":"$episodeNumber","expired_date":"${getExpiryDate()}","platform":"android","tid":"$watchId","oss":"1","uid":"14710508","appid":"$APP_ID","season":"$seasonToUse","lang":"en","group":""}"""
        }

        val downloadResponse = client.superStreamCall<CommonResponse<StreamData>>(query)
        downloadResponse?.msg?.raiseOnError("Failed to fetch source.")

        val validItem = downloadResponse?.data?.list
            ?.firstOrNull { it.fid != null }
            ?: throw Exception("Cannot find source")

        // Should really run this query for every link :(
        val subtitleQuery = if (isMovie) {
            """{"childmode":"0","fid":"${validItem.fid}","uid":"14710508","app_version":"$APP_VERSION","appid":"$APP_ID","module":"Movie_srt_list_v2","channel":"Website","mid":"$watchId","lang":"en","expired_date":"${getExpiryDate()}","platform":"android"}"""
        } else {
            """{"childmode":"0","fid":"${validItem.fid}","app_version":"$APP_VERSION","module":"TV_srt_list_v2","channel":"Website","episode":"$episodeNumber","expired_date":"${getExpiryDate()}","platform":"android","tid":"$watchId","uid":"14710508","appid":"$APP_ID","season":"$seasonToUse","lang":"en"}"""
        }

        val subtitlesResponse = client.superStreamCall<CommonResponse<SubtitleData>>(subtitleQuery)
        subtitlesResponse?.msg?.raiseOnError("Failed to fetch subtitles.")

        asyncCalls(
            {
                subtitlesResponse?.data?.list?.forEach topForEach@ { item ->
                    item.subtitles
                        .sortedWith(compareByDescending { it.order })
                        .forEach {
                            val validFile = it.filePath != null && it.lang != null
                            if (!validFile)
                                return@topForEach

                            val subtitle = Subtitle(
                                language = """
                                    [SS] ${it.language ?: "UNKNOWN"} (${it.lang}) 
                                    Votes 👍: ${it.order}
                                """.trimIndent(),
                                url = it.filePath!!.toValidSubtitleFilePath()
                            )
                            onLinkFound(subtitle)
                        }
                }
            },
            {
                downloadResponse.data.list.forEach { item ->
                    val stream = if (!item.path.isNullOrBlank()) {
                        Stream(
                            name = """
                                [SS] ${item.quality}
                                Views 👁️: ${item.count}
                                File 📹: ${item.filename}
                                Size 💾: ${item.size}
                            """.trimIndent(),
                            url = item.path!!
                        )
                    } else {
                        Stream(
                            name = """
                                [SS] ${item.quality}
                                Views 👁️: ${item.count}
                                File 📹: ${item.filename}
                                Size 💾: ${item.size}
                            """.trimIndent(),
                            url = item.path!!
                        )
                    }

                    onLinkFound(stream)
                }
            }
        )
    }

    private fun getExternalStream(fid: String) {

    }
}