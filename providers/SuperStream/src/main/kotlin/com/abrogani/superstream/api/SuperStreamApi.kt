package com.abrogani.superstream.api

import android.content.res.Resources
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.abrogani.superstream.BuildConfig
import com.abrogani.superstream.api.dto.BoxType
import com.abrogani.superstream.api.dto.ExternalResponse
import com.abrogani.superstream.api.dto.ExternalSources
import com.abrogani.superstream.api.dto.ITEMS_PER_PAGE
import com.abrogani.superstream.api.dto.SearchData
import com.abrogani.superstream.api.dto.old.CommonResponse
import com.abrogani.superstream.api.dto.old.MediaMetadata
import com.abrogani.superstream.api.util.ResourceUtil.getRawFileInputStream
import com.abrogani.superstream.api.util.old.CLIENT_CA4_NAME
import com.abrogani.superstream.api.util.old.CLIENT_CA_NAME
import com.abrogani.superstream.api.util.old.CLIENT_CERT_NAME
import com.abrogani.superstream.api.util.old.CLIENT_KEY_NAME
import com.abrogani.superstream.api.util.old.Constants.APP_ID
import com.abrogani.superstream.api.util.old.Constants.APP_VERSION
import com.abrogani.superstream.api.util.old.CustomCertificateClient
import com.abrogani.superstream.api.util.old.SuperStreamUtil.getExpiryDate
import com.abrogani.superstream.api.util.old.SuperStreamUtil.raiseOnError
import com.abrogani.superstream.api.util.old.SuperStreamUtil.superStreamCall
import com.abrogani.superstream.settings.TOKEN_KEY
import com.flixclusive.core.util.coroutines.FlxDispatchers.Companion.withDefaultContext
import com.flixclusive.core.util.coroutines.FlxDispatchers.Companion.withIOContext
import com.flixclusive.core.util.coroutines.asyncCalls
import com.abrogani.superstream.api.util.fromJson
import com.flixclusive.core.util.network.okhttp.request
import com.flixclusive.model.media.MediaMetadata as FlxMediaMetadata
import com.flixclusive.model.media.Movie
import com.flixclusive.model.media.PartialMedia
import com.flixclusive.model.media.Show
import com.flixclusive.model.media.common.MediaIdSource
import com.flixclusive.model.media.common.MediaType
import com.flixclusive.model.media.common.PaginatedMedia
import com.flixclusive.model.media.common.tv.Episode
import com.flixclusive.model.media.common.tv.Season
import com.flixclusive.model.provider.link.MediaLink
import com.flixclusive.model.provider.link.Stream
import com.flixclusive.model.provider.link.Subtitle
import com.flixclusive.model.provider.link.SubtitleSource
import com.flixclusive.provider.capability.CrossMatchProviderApi
import com.flixclusive.provider.capability.MediaLinkProviderApi
import com.flixclusive.provider.capability.MediaLinkType
import com.flixclusive.provider.capability.MediaMetadataProviderApi
import com.flixclusive.provider.capability.SearchProviderApi
import com.flixclusive.provider.extensions.getString
import com.flixclusive.provider.filter.FilterList
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

private const val PROVIDER_NAME = "SuperStream"

class SuperStreamApi(
    private val client: OkHttpClient,
    private val providerId: String,
    resources: Resources,
    private val settings: DataStore<Preferences>
) : SearchProviderApi, MediaMetadataProviderApi, CrossMatchProviderApi, MediaLinkProviderApi {
    private val specialClient: OkHttpClient

    init {
        val certStream = getRawFileInputStream(resources, CLIENT_CERT_NAME)
        val keyStream = getRawFileInputStream(resources, CLIENT_KEY_NAME)
        val caStream = getRawFileInputStream(resources, CLIENT_CA_NAME)
        val ca4Stream = getRawFileInputStream(resources, CLIENT_CA4_NAME)

        val certificateClient = CustomCertificateClient()

        specialClient = certificateClient.createOkHttpClient(
            clientCertStream = certStream,
            caCertStreams = listOf(caStream, ca4Stream),
            privateKeyInputStream = keyStream,
            privateKeyPassword = null
        )
    }

    override val filters: FilterList = FilterList()

    override val supportedLinkTypes = setOf(MediaLinkType.STREAMS, MediaLinkType.SUBTITLES)

    override suspend fun getLinks(
        media: FlxMediaMetadata,
        episode: Episode?,
        onLinkFound: (MediaLink) -> Unit
    ) {
        val token = requireNotNull(settings.getString(TOKEN_KEY, null)) {
            "No token found! Go to $PROVIDER_NAME's settings and configure it."
        }

        getSourceLinksFromFourthApi(
            watchId = media.id,
            filmType = BoxType.fromMediaType(media.type),
            season = episode?.season,
            episode = episode?.number,
            token = token,
            onLinkFound = onLinkFound
        )
    }

    override suspend fun search(
        query: String,
        page: Int,
        filters: FilterList,
    ): PaginatedMedia<PartialMedia> {
        val apiQuery =
            // Originally 8 pagelimit
            """{"childmode":"0","app_version":"$APP_VERSION","appid":"$APP_ID","module":"Search5","channel":"Website","page":"$page","lang":"en","type":"all","keyword":"$query","pagelimit":"$ITEMS_PER_PAGE","expired_date":"${getExpiryDate()}","platform":"android"}"""

        val response = specialClient.superStreamCall<SearchData>(query = apiQuery)

        val mappedItems = response?.results?.map { it.toPartialMedia(providerId) }
            ?: throw NullPointerException("Cannot search on SuperStream")

        return PaginatedMedia(
            page = page,
            results = mappedItems,
            hasNextPage = (page * ITEMS_PER_PAGE) < response.total
        )
    }

    override suspend fun getMovie(media: PartialMedia): Movie {
        val apiQuery =
            """{"childmode":"0","uid":"","app_version":"$APP_VERSION","appid":"$APP_ID","module":"Movie_detail","channel":"Website","mid":"${media.id}","lang":"en","expired_date":"${getExpiryDate()}","platform":"android","oss":"","group":""}"""

        return fetchMetadata(apiQuery).toMovie(providerId)
    }

    override suspend fun getShow(media: PartialMedia): Show {
        val apiQuery =
            """{"childmode":"0","uid":"","app_version":"$APP_VERSION","appid":"$APP_ID","module":"TV_detail_1","display_all":"1","channel":"Website","lang":"en","expired_date":"${getExpiryDate()}","platform":"android","tid":"${media.id}"}"""

        return fetchMetadata(apiQuery).toShow(providerId)
    }

    // Shows are returned without seasons; episodes are resolved by the host through other providers.
    override suspend fun getSeason(show: Show, season: Season.Partial): Season.Full? = null

    private fun fetchMetadata(apiQuery: String): MediaMetadata {
        val response = specialClient.superStreamCall<CommonResponse<MediaMetadata>>(apiQuery)
        response?.msg?.raiseOnError("Failed to fetch movie info.")

        return response?.data!!
    }

    override suspend fun getById(
        mediaType: MediaType,
        sourceIds: Map<MediaIdSource, String>,
    ): FlxMediaMetadata? {
        // The site's search accepts an IMDb id as the keyword.
        val imdbId = sourceIds[MediaIdSource.IMDB] ?: return null

        val match = search(query = imdbId).results.firstOrNull { it.type == mediaType }
        return match?.let { toMetadata(it) }
    }

    override suspend fun getByFuzzy(media: FlxMediaMetadata): FlxMediaMetadata? {
        val match = search(query = media.title).results.firstOrNull { candidate ->
            candidate.type == media.type &&
                candidate.title.equals(media.title, ignoreCase = true) &&
                candidate.releaseDate.sameYearAs(media.releaseDate)
        }

        return match?.let { toMetadata(it) }
    }

    private suspend fun toMetadata(media: PartialMedia): FlxMediaMetadata =
        if (media.isMovie) getMovie(media) else getShow(media)

    private fun Long?.sameYearAs(other: Long?): Boolean {
        if (this == null || other == null) return true // can't tell, don't reject on it

        val zone = java.time.ZoneOffset.UTC
        fun Long.year() = java.time.Instant.ofEpochMilli(this).atZone(zone).year
        return year() == other.year()
    }

    private suspend fun getSourceLinksFromFourthApi(
        watchId: String,
        filmType: BoxType,
        episode: Int?,
        season: Int?,
        token: String,
        onLinkFound: (MediaLink) -> Unit
    ) {
        val shareKey = fetchShareKey(
            watchId = watchId, filmType = filmType
        )
        val sharedResources = fetchSharedResources(shareKey)
        val files = fetchFiles(
            sharedResources = sharedResources,
            season = season,
            episode = episode,
            shareKey = shareKey
        )
        val cleanedFiles = cleanFiles(
            files = files, shareKey = shareKey
        )

        withDefaultContext {
            processFiles(
                cleanedFiles = cleanedFiles,
                shareKey = shareKey,
                token = token,
                onLinkFound = onLinkFound
            )
        }
    }

    private fun fetchShareKey(watchId: String, filmType: BoxType): String {
//        val interceptor = withMainContext {
//            CloudflareWebViewInterceptor(context)
//        }

//        val url =
//            "${BuildConfig.SUPERSTREAM_FIRST_API}/index/share_link?id=$watchId&type=${filmType.value}"
        val url =
            "${BuildConfig.SUPERSTREAM_SECOND_API}/mbp/to_share_page?box_type=${filmType.value}&mid=$watchId&json=1"

        val response = client
//            .addWebViewInterceptor(interceptor)
            .request(url)
            .execute()

        val parsedResponse = response.fromJson<ExternalResponse>(
            errorMessage = "[$PROVIDER_NAME] Failed to fetch share key."
        )

        return parsedResponse.data?.link?.substringAfterLast("/")
            ?: throw Exception("[$PROVIDER_NAME] No share key found.")
    }

    private fun fetchSharedResources(shareKey: String): ExternalResponse.Data {
        val url = "${BuildConfig.SUPERSTREAM_SECOND_API}/file/file_share_list?share_key=$shareKey"
        val humanizedHeaders = mapOf("Accept-Language" to "en")
        return client.request(url, headers = humanizedHeaders.toHeaders())
            .execute()
            .fromJson<ExternalResponse>("[$PROVIDER_NAME] Can't fetch file lists.")
            .data ?: throw Exception("[$PROVIDER_NAME] No shared resources found (Stage 1).")
    }

    private suspend fun fetchFiles(
        sharedResources: ExternalResponse.Data,
        season: Int?,
        episode: Int?,
        shareKey: String
    ): List<ExternalResponse.Data.StreamFile> {
        return if (season == null) {
            sharedResources.files
        } else {
            withDefaultContext {
                fetchSeasonFiles(
                    sharedResources = sharedResources,
                    season = season,
                    episode = episode!!,
                    shareKey = shareKey
                )
            }
        } ?: throw Exception("[$PROVIDER_NAME] No FIDs found.")
    }

    private suspend fun fetchSeasonFiles(
        sharedResources: ExternalResponse.Data,
        season: Int,
        episode: Int,
        shareKey: String
    ): List<ExternalResponse.Data.StreamFile>? {
        val parentId = findSeasonParentId(sharedResources, season, shareKey)
        val url =
            "${BuildConfig.SUPERSTREAM_SECOND_API}/file/file_share_list?share_key=$shareKey&parent_id=$parentId&page=1"

        val episodesShareRes = withIOContext {
            client.request(url)
                .execute()
                .fromJson<ExternalResponse>(
                    errorMessage = "[$PROVIDER_NAME] Failed to fetch share key."
                )
                .data ?: throw Exception("[$PROVIDER_NAME] No shared resources found (Stage 2).")
        }

        val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)
        return episodesShareRes.files?.filter {
            it.fileName.contains("s${seasonSlug}e${episodeSlug}", true)
        }
    }

    private suspend fun findSeasonParentId(
        sharedResources: ExternalResponse.Data,
        season: Int,
        shareKey: String
    ): Long {
        var parentId = sharedResources.files
            ?.find { it.fileName.equals("season $season", true) }?.fid

        val filesAreNotEmpty = sharedResources.files?.isNotEmpty() == true
        if (parentId == null && filesAreNotEmpty) {
            val cleanedFiles = cleanFiles(
                files = sharedResources.files,
                shareKey = shareKey
            )

            parentId = cleanedFiles
                .find { it.fileName.equals("season $season", true) }?.fid
        }

        if (parentId == null && filesAreNotEmpty) {
            val subDirectoryId = sharedResources.files.first().fid
            parentId = withIOContext {
                fetchSubDirectorySeasonId(subDirectoryId, season, shareKey)
            }
        }

        return requireNotNull(parentId) { "[$PROVIDER_NAME] No shared resources found (Stage 2)." }
    }

    private fun fetchSubDirectorySeasonId(
        subDirectoryId: Long?,
        season: Int,
        shareKey: String
    ): Long? {
        val url =
            "${BuildConfig.SUPERSTREAM_SECOND_API}/file/file_share_list?share_key=$shareKey&parent_id=$subDirectoryId&page=1"

        return client.request(url)
            .execute()
            .fromJson<ExternalResponse>("[$PROVIDER_NAME] Failed to fetch share key.")
            .data?.files
            ?.find { it.fileName.equals("season $season", true) }?.fid
    }

    private suspend fun cleanFiles(
        files: List<ExternalResponse.Data.StreamFile>,
        shareKey: String
    ): List<ExternalResponse.Data.StreamFile> {
        val cleanedFiles = mutableListOf<ExternalResponse.Data.StreamFile>()
        withDefaultContext {
            files.forEach {
                if (it.isDirectory) {
                    val filesFromDirectory = withIOContext {
                        fetchFilesFromDirectory(it.fid, shareKey)
                    }
                    cleanedFiles.addAll(filesFromDirectory)
                } else {
                    cleanedFiles.add(it)
                }
            }
        }

        return cleanedFiles
    }

    private fun fetchFilesFromDirectory(
        fid: Long?,
        shareKey: String
    ): List<ExternalResponse.Data.StreamFile> {
        val url =
            "${BuildConfig.SUPERSTREAM_SECOND_API}/file/file_share_list?share_key=$shareKey&parent_id=$fid"
        val humanizedHeaders = mapOf("Accept-Language" to "en")
        return client.request(url, headers = humanizedHeaders.toHeaders())
            .execute()
            .fromJson<ExternalResponse>("[$PROVIDER_NAME] Failed to fetch directory files.")
            .data?.files
            ?: throw Exception("[$PROVIDER_NAME] No files found on this directory.")
    }

    private suspend fun processFiles(
        cleanedFiles: List<ExternalResponse.Data.StreamFile>,
        shareKey: String,
        token: String,
        onLinkFound: (MediaLink) -> Unit
    ) {
        cleanedFiles.forEach { fileList ->
            val playerUrl =
                "${BuildConfig.SUPERSTREAM_SECOND_API}/file/player?fid=${fileList.fid}&share_key=$shareKey"
            val player = withIOContext { fetchPlayerContent(playerUrl, token) }
            val (subtitles, sources, qualities) = extractPlayerData(player)

            asyncCalls(
                {
                    processSubtitles(
                        subtitles = subtitles,
                        onLinkFound = onLinkFound
                    )
                },
                {
                    processSources(
                        sources = sources,
                        qualities = qualities,
                        file = fileList,
                        onLinkFound = onLinkFound
                    )
                }
            )
        }
    }

    private fun fetchPlayerContent(playerUrl: String, token: String): String {
        val humanizedHeaders = mapOf("Accept-Language" to "en")
        return client.request(
            url = playerUrl,
            headers = (humanizedHeaders + mapOf("Cookie" to "ui=$token")).toHeaders()
        ).execute().body.string()
    }

    private fun extractPlayerData(player: String): Triple<String?, String?, String?> {
        val subtitles =
            Regex("""\$\(".jw-wrapper"\).prepend\('(.*)'\)""").find(player)?.groupValues?.get(1)
        val sources = Regex("sources\\s*=\\s*(.*);").find(player)?.groupValues?.get(1)
        val qualities = Regex("quality_list\\s*=\\s*(.*);").find(player)?.groupValues?.get(1)
        return Triple(subtitles, sources, qualities)
    }

    private fun processSubtitles(
        subtitles: String?,
        onLinkFound: (MediaLink) -> Unit
    ) {
        if (subtitles == null) return

        val jsoupObject = Jsoup.parse(subtitles)
        val div = jsoupObject.selectFirst("div.right2") ?: return
        val languages = div.select("ul")

        languages.forEach { languageElement ->
            val languageId = languageElement.id() ?: "UNKNOWN"
            val subtitleLinks = languageElement.select("li")

            subtitleLinks.forEach { subtitleLink ->
                val link = subtitleLink.attr("data-url")
                val fileName = subtitleLink.selectFirst("p")?.text() ?: "UNKNOWN SUBTITLE"
                val subtitle = Subtitle(
                    language = """
                        Subtitle 🗯️: $fileName
                        Language 🌐: $languageId
                    """.trimIndent(),
                    url = link,
                    type = SubtitleSource.ONLINE
                )

                onLinkFound(subtitle)
            }
        }
    }

    private fun processSources(
        sources: String?,
        qualities: String?,
        file: ExternalResponse.Data.StreamFile,
        onLinkFound: (MediaLink) -> Unit
    ) {
        listOf(sources, qualities).forEach topForEach@{ item ->
            if (item == null) return@topForEach

            fromJson<List<ExternalSources>>(item).forEach { source ->
                if (!source.label.equals("AUTO", true) && !source.type.equals(
                        "video/mp4",
                        true
                    )
                ) return@forEach

                val url = (source.hlsUrl ?: source.file)?.replace("\\/", "/") ?: return@forEach

                onLinkFound(
                    Stream(
                        name = """
                            =< SuperStream >=
                            Quality 👁️: ${source.label}
                            File 📹: ${file.fileName}
                            Size 💾: ${file.fileSize}
                            Date ⌚: ${file.addedOn}
                            =================
                        """.trimIndent(),
                        url = url
                    )
                )
            }
        }
    }

    private fun getEpisodeSlug(season: Int? = null, episode: Int? = null): Pair<String, String> {
        if (season == null && episode == null) {
            return "" to ""
        }

        val seasonSlug = season?.let { if (it < 10) "0$it" else "$it" } ?: ""
        val episodeSlug = episode?.let { if (it < 10) "0$it" else "$it" } ?: ""

        return seasonSlug to episodeSlug
    }
}