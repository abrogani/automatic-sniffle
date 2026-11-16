package com.abrogani.servetorr.engine

import com.abrogani.servetorr.link.TorrentFile
import com.flixclusive.core.util.coroutines.FlxDispatchers.Companion.withIOContext
import com.flixclusive.core.util.log.warnLog
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal class TorrServerClient(client: OkHttpClient) {
    private val http = client.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    private val echoClient = http.newBuilder()
        .callTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    suspend fun echo(port: Int): Boolean =
        try {
            val request = Request.Builder().url("${base(port)}/echo").build()
            echoClient.newCall(request).await().use { it.isSuccessful && isEngineEcho(withIOContext { it.body.string() }) }
        } catch (e: IOException) {
            false
        }

    suspend fun waitForEcho(port: Int, timeoutMillis: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            if (echo(port)) return true
            if (System.currentTimeMillis() >= deadline) return false
            delay(ECHO_POLL_MILLIS)
        }
    }

    suspend fun metadata(
        port: Int,
        source: String,
        knownHash: String?,
        timeoutMillis: Long,
        drops: StateFlow<Int>,
    ): TorrentMetadata =
        withTimeoutOrNull(timeoutMillis) { resolve(port, source, knownHash, drops) }
            ?: throw TorrentMetadataTimeout("Timed out waiting for torrent metadata (${knownHash ?: "added link"})")

    private suspend fun resolve(port: Int, source: String, knownHash: String?, drops: StateFlow<Int>): TorrentMetadata {
        var readded = false
        while (true) {
            val generation = drops.value
            val result = coroutineScope {
                val add = async { addAndWait(port, source, knownHash) }
                val dropped = async { awaitDrop(port, knownHash, generation, drops) }
                select<TorrentMetadata?> {
                    add.onAwait { it }
                    dropped.onAwait { null }
                }.also { coroutineContext.cancelChildren() }
            }
            if (result != null) return result
            if (readded) throw IOException("The torrent was dropped while its metadata was loading")
            readded = true
            warnLog("[ServeTorr] Torrent dropped while loading its metadata; adding it again")
        }
    }

    private suspend fun addAndWait(port: Int, source: String, knownHash: String?): TorrentMetadata? {
        var status = post(port, TorrentRequest(action = "add", link = source, saveToDb = false))
            ?: throw IOException("TorrServer returned no status for the added torrent")
        val hash = status.hash?.takeIf { it.isNotBlank() } ?: knownHash
            ?: throw IOException("TorrServer returned no info hash for the added torrent")

        while (true) {
            val files = status.toTorrentFiles()
            if (files.isNotEmpty()) return TorrentMetadata(hash.lowercase(), files)

            delay(METADATA_POLL_MILLIS)
            status = post(port, TorrentRequest(action = "get", hash = hash)) ?: return null
        }
    }

    private suspend fun awaitDrop(port: Int, knownHash: String?, generation: Int, drops: StateFlow<Int>) {
        var seen = false
        while (drops.value == generation) {
            if (knownHash != null) {
                val present = try {
                    post(port, TorrentRequest(action = "get", hash = knownHash)) != null
                } catch (e: IOException) {
                    seen
                }
                if (present) seen = true else if (seen) return
            }
            delay(DROP_POLL_MILLIS)
        }
    }

    suspend fun list(port: Int): List<TorrentStatus> {
        val body = postRaw(port, "torrents", gson.toJson(TorrentRequest(action = "list"))) ?: return emptyList()
        return try {
            gson.fromJson(body, Array<TorrentStatus>::class.java)?.toList().orEmpty()
        } catch (e: JsonParseException) {
            throw IOException("Unexpected TorrServer response: ${body.take(200)}", e)
        }
    }

    suspend fun drop(port: Int, hash: String) {
        postRaw(port, "torrents", gson.toJson(TorrentRequest(action = "drop", hash = hash)))
    }

    suspend fun remove(port: Int, hash: String) {
        postRaw(port, "torrents", gson.toJson(TorrentRequest(action = "rem", hash = hash)))
    }

    suspend fun wipe(port: Int) {
        postRaw(port, "torrents", gson.toJson(TorrentRequest(action = "wipe")))
    }

    suspend fun viewedHashes(port: Int): List<String> {
        val body = postRaw(port, "viewed", """{"action":"list"}""") ?: return emptyList()
        return try {
            gson.fromJson(body, Array<ViewedEntry>::class.java)?.mapNotNull { it?.hash }?.distinct().orEmpty()
        } catch (e: JsonParseException) {
            throw IOException("Unexpected TorrServer response: ${body.take(200)}", e)
        }
    }

    suspend fun removeViewed(port: Int, hash: String) {
        postRaw(port, "viewed", gson.toJson(ViewedRequest(action = "rem", hash = hash, fileIndex = -1)))
    }

    suspend fun getSettings(port: Int): JsonObject {
        val body = postRaw(port, "settings", """{"action":"get"}""")
            ?: throw IOException("TorrServer returned no settings")
        return try {
            JsonParser.parseString(body).asJsonObject
        } catch (e: RuntimeException) {
            throw IOException("Unexpected TorrServer settings: ${body.take(200)}", e)
        }
    }

    suspend fun setSettings(port: Int, sets: JsonObject) {
        val body = JsonObject().apply {
            addProperty("action", "set")
            add("sets", sets)
        }
        postRaw(port, "settings", gson.toJson(body))
    }

    suspend fun resetSettings(port: Int) {
        postRaw(port, "settings", """{"action":"def"}""")
    }

    private suspend fun post(port: Int, body: TorrentRequest): TorrentStatus? {
        val raw = postRaw(port, "torrents", gson.toJson(body)) ?: return null
        return try {
            gson.fromJson(raw, TorrentStatus::class.java)
        } catch (e: JsonParseException) {
            throw IOException("Unexpected TorrServer response: ${raw.take(200)}", e)
        }
    }

    private suspend fun postRaw(port: Int, path: String, json: String): String? {
        val request = Request.Builder()
            .url("${base(port)}/$path")
            .post(json.toRequestBody(JSON))
            .build()

        return http.newCall(request).await().use { response ->
            if (response.code == 404) return null
            val text = withIOContext { response.body.string() }
            if (!response.isSuccessful) throw IOException("TorrServer /$path failed [${response.code}]: ${text.take(200)}")
            text
        }
    }

    internal data class TorrentMetadata(val hash: String, val files: List<TorrentFile>)

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
        val stat: Int? = null,
        @SerializedName("stat_string") val statString: String? = null,
        @SerializedName("torrent_size") val torrentSize: Long? = null,
        @SerializedName("loaded_size") val loadedSize: Long? = null,
        @SerializedName("total_peers") val totalPeers: Int? = null,
        @SerializedName("active_peers") val activePeers: Int? = null,
        @SerializedName("connected_seeders") val connectedSeeders: Int? = null,
        @SerializedName("download_speed") val downloadSpeed: Double? = null,
        @SerializedName("upload_speed") val uploadSpeed: Double? = null,
    )

    internal data class ViewedEntry(val hash: String? = null)

    internal data class ViewedRequest(
        val action: String,
        val hash: String,
        @SerializedName("file_index") val fileIndex: Int,
    )

    internal data class FileStat(
        val id: Int? = null,
        val path: String? = null,
        val length: Long? = null,
    )

    class TorrentMetadataTimeout(message: String) : IOException(message)

    internal companion object {
        const val ECHO_SIGNATURE = "ServeTorr torrserver ${EngineSource.VERSION}"

        private const val ECHO_POLL_MILLIS = 250L
        private const val METADATA_POLL_MILLIS = 500L
        private const val DROP_POLL_MILLIS = 1_000L
        private val JSON = "application/json; charset=utf-8".toMediaType()

        val gson = Gson()

        fun base(port: Int) = "http://127.0.0.1:$port"

        fun isEngineEcho(body: String?): Boolean = body?.trim() == ECHO_SIGNATURE

        fun parseStatus(json: String): TorrentStatus? = gson.fromJson(json, TorrentStatus::class.java)

        fun TorrentStatus.toTorrentFiles(): List<TorrentFile> =
            fileStats.orEmpty().mapNotNull { stat ->
                val path = stat?.path?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                TorrentFile(id = stat.id ?: return@mapNotNull null, path = path, length = stat.length ?: 0L)
            }

        suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            })
        }
    }
}
