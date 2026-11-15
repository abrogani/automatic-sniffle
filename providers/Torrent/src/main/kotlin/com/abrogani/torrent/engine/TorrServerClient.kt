package com.abrogani.torrent.engine

import com.abrogani.torrent.link.TorrentFile
import com.flixclusive.core.util.coroutines.FlxDispatchers.Companion.withIOContext
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** TorrServer's HTTP API on the loopback interface. */
internal class TorrServerClient(client: OkHttpClient) {
    private val http = client.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    private val echoClient = http.newBuilder()
        .callTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    /** True when something answers TorrServer's `/echo` on [port]. */
    suspend fun echo(port: Int): Boolean = withIOContext {
        try {
            echoClient.newCall(Request.Builder().url("${base(port)}/echo").build()).execute().use {
                it.isSuccessful && it.body.string().isNotBlank()
            }
        } catch (e: IOException) {
            false
        }
    }

    suspend fun waitForEcho(port: Int, timeoutMillis: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            if (echo(port)) return true
            if (System.currentTimeMillis() >= deadline) return false
            delay(ECHO_POLL_MILLIS)
        }
    }

    /**
     * Adds [source] without saving it to TorrServer's db and polls until its metadata (file list) arrives.
     * `add` itself returns before the metadata does (it fetches it in the background).
     */
    suspend fun files(port: Int, source: String, knownHash: String?, timeoutMillis: Long): List<TorrentFile> {
        val deadline = System.currentTimeMillis() + timeoutMillis

        var status = post(port, TorrentRequest(action = "add", link = source, saveToDb = false))
            ?: throw IOException("TorrServer returned no status for the added torrent")
        val hash = status.hash?.takeIf { it.isNotBlank() } ?: knownHash
            ?: throw IOException("TorrServer returned no info hash for the added torrent")

        while (true) {
            val files = status.toTorrentFiles()
            if (files.isNotEmpty()) return files
            if (System.currentTimeMillis() >= deadline) {
                throw TorrentMetadataTimeout("Timed out waiting for torrent metadata ($hash)")
            }

            delay(METADATA_POLL_MILLIS)
            status = post(port, TorrentRequest(action = "get", hash = hash)) ?: status
        }
    }

    suspend fun list(port: Int): List<TorrentStatus> = withIOContext {
        val body = postRaw(port, TorrentRequest(action = "list")) ?: return@withIOContext emptyList()
        gson.fromJson(body, Array<TorrentStatus>::class.java)?.toList().orEmpty()
    }

    suspend fun drop(port: Int, hash: String) {
        postRaw(port, TorrentRequest(action = "drop", hash = hash))
    }

    private suspend fun post(port: Int, body: TorrentRequest): TorrentStatus? {
        val raw = postRaw(port, body) ?: return null
        return try {
            gson.fromJson(raw, TorrentStatus::class.java)
        } catch (e: JsonParseException) {
            throw IOException("Unexpected TorrServer response: ${raw.take(200)}", e)
        }
    }

    /** The response body, or null for 404 (TorrServer's answer for an unknown hash). */
    private suspend fun postRaw(port: Int, body: TorrentRequest): String? = withIOContext {
        val request = Request.Builder()
            .url("${base(port)}/torrents")
            .post(gson.toJson(body).toRequestBody(JSON))
            .build()

        http.newCall(request).execute().use { response ->
            if (response.code == 404) return@withIOContext null
            val text = response.body.string()
            if (!response.isSuccessful) throw IOException("TorrServer ${body.action} failed [${response.code}]: ${text.take(200)}")
            text
        }
    }

    internal data class TorrentRequest(
        val action: String,
        val link: String? = null,
        val hash: String? = null,
        @SerializedName("save_to_db") val saveToDb: Boolean? = null,
    )

    internal data class TorrentStatus(
        val hash: String? = null,
        val name: String? = null,
        val title: String? = null,
        @SerializedName("file_stats") val fileStats: List<FileStat?>? = null,
    )

    internal data class FileStat(
        val id: Int? = null,
        val path: String? = null,
        val length: Long? = null,
    )

    /** Thrown instead of a [kotlinx.coroutines.TimeoutCancellationException] so callers don't mistake it for cancellation. */
    class TorrentMetadataTimeout(message: String) : IOException(message)

    internal companion object {
        private const val ECHO_POLL_MILLIS = 250L
        private const val METADATA_POLL_MILLIS = 500L
        private val JSON = "application/json; charset=utf-8".toMediaType()

        val gson = Gson()

        fun base(port: Int) = "http://127.0.0.1:$port"

        fun parseStatus(json: String): TorrentStatus? = gson.fromJson(json, TorrentStatus::class.java)

        fun TorrentStatus.toTorrentFiles(): List<TorrentFile> =
            fileStats.orEmpty().mapNotNull { stat ->
                val id = stat?.id ?: return@mapNotNull null
                val path = stat.path?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                TorrentFile(id = id, path = path, length = stat.length ?: 0L)
            }
    }
}
