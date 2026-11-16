package com.abrogani.servetorr.engine

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.annotations.SerializedName

internal data class EngineSettings(
    val cacheSizeMb: Int = 64,
    val readAheadPercent: Int = 95,
    val preloadPercent: Int = 50,
    val useDisk: Boolean = false,
    val removeCacheOnDrop: Boolean = false,
    val forceEncrypt: Boolean = false,
    val retrackersMode: Int = 1,
    val disconnectTimeoutSeconds: Int = 30,
    val enableDebug: Boolean = false,
    val enableIpv6: Boolean = false,
    val disableTcp: Boolean = false,
    val disableUtp: Boolean = false,
    val disableUpnp: Boolean = false,
    val disableDht: Boolean = false,
    val disablePex: Boolean = false,
    val enableLpd: Boolean = false,
    val disableUpload: Boolean = false,
    val downloadRateLimitKib: Int = 0,
    val uploadRateLimitKib: Int = 0,
    val connectionsLimit: Int = 25,
    val peersListenPort: Int = 0,
    val trackers: List<String> = EngineSource.DEFAULT_TRACKERS,
) {
    fun errors(): List<String> = buildList {
        if (cacheSizeMb !in 32..2048) add("Cache size must be between 32 and 2048 MB.")
        if (readAheadPercent !in 5..100) add("Read-ahead must be between 5 and 100%.")
        if (preloadPercent !in 0..100) add("Preload must be between 0 and 100%.")
        if (retrackersMode !in 0..3) add("Tracker mode must be 0 (keep), 1 (add), 2 (remove) or 3 (replace).")
        if (disconnectTimeoutSeconds !in 1..3600) add("Disconnect timeout must be between 1 and 3600 seconds.")
        if (disableTcp && disableUtp) add("TCP and uTP can't both be disabled.")
        if (downloadRateLimitKib < 0) add("Download limit can't be negative.")
        if (uploadRateLimitKib < 0) add("Upload limit can't be negative.")
        if (connectionsLimit !in 1..200) add("Connections limit must be between 1 and 200.")
        if (peersListenPort != 0 && peersListenPort !in 1024..65535) {
            add("Peer port must be 0 (random) or between 1024 and 65535.")
        }
        trackers.filterNot { TRACKER.matches(it) }.forEach { add("Invalid tracker: $it") }
    }

    private companion object {
        val TRACKER = Regex("""(udp|https?|wss?)://\S+""", RegexOption.IGNORE_CASE)
    }
}

internal data class BTSets(
    @SerializedName("CacheSize") val cacheSize: Long = 0,
    @SerializedName("ReaderReadAHead") val readerReadAhead: Int = 0,
    @SerializedName("PreloadCache") val preloadCache: Int = 0,
    @SerializedName("UseDisk") val useDisk: Boolean = false,
    @SerializedName("TorrentsSavePath") val torrentsSavePath: String = "",
    @SerializedName("RemoveCacheOnDrop") val removeCacheOnDrop: Boolean = false,
    @SerializedName("ForceEncrypt") val forceEncrypt: Boolean = false,
    @SerializedName("RetrackersMode") val retrackersMode: Int = 0,
    @SerializedName("TorrentDisconnectTimeout") val torrentDisconnectTimeout: Int = 0,
    @SerializedName("EnableDebug") val enableDebug: Boolean = false,
    @SerializedName("EnableDLNA") val enableDlna: Boolean = false,
    @SerializedName("FriendlyName") val friendlyName: String = "",
    @SerializedName("EnableIPv6") val enableIpv6: Boolean = false,
    @SerializedName("DisableTCP") val disableTcp: Boolean = false,
    @SerializedName("DisableUTP") val disableUtp: Boolean = false,
    @SerializedName("DisableUPNP") val disableUpnp: Boolean = false,
    @SerializedName("DisableDHT") val disableDht: Boolean = false,
    @SerializedName("DisablePEX") val disablePex: Boolean = false,
    @SerializedName("EnableLPD") val enableLpd: Boolean = false,
    @SerializedName("DisableUpload") val disableUpload: Boolean = false,
    @SerializedName("DownloadRateLimit") val downloadRateLimit: Int = 0,
    @SerializedName("UploadRateLimit") val uploadRateLimit: Int = 0,
    @SerializedName("ConnectionsLimit") val connectionsLimit: Int = 0,
    @SerializedName("PeersListenPort") val peersListenPort: Int = 0,
)

internal object EngineSettingsCodec {
    const val STORE_KEY = "engine_settings"
    private const val MB = 1024L * 1024

    private val gson = Gson()

    fun EngineSettings.toBTSets(savePath: String, base: BTSets = BTSets()): BTSets = base.copy(
        cacheSize = cacheSizeMb * MB,
        readerReadAhead = readAheadPercent,
        preloadCache = preloadPercent,
        useDisk = useDisk,
        torrentsSavePath = savePath,
        removeCacheOnDrop = removeCacheOnDrop,
        forceEncrypt = forceEncrypt,
        retrackersMode = retrackersMode,
        torrentDisconnectTimeout = disconnectTimeoutSeconds,
        enableDebug = enableDebug,
        enableIpv6 = enableIpv6,
        disableTcp = disableTcp,
        disableUtp = disableUtp,
        disableUpnp = disableUpnp,
        disableDht = disableDht,
        disablePex = disablePex,
        enableLpd = enableLpd,
        disableUpload = disableUpload,
        downloadRateLimit = downloadRateLimitKib,
        uploadRateLimit = uploadRateLimitKib,
        connectionsLimit = connectionsLimit,
        peersListenPort = peersListenPort,
    )

    fun BTSets.toEngineSettings(trackers: List<String> = EngineSource.DEFAULT_TRACKERS) = EngineSettings(
        cacheSizeMb = (cacheSize / MB).toInt(),
        readAheadPercent = readerReadAhead,
        preloadPercent = preloadCache,
        useDisk = useDisk,
        removeCacheOnDrop = removeCacheOnDrop,
        forceEncrypt = forceEncrypt,
        retrackersMode = retrackersMode,
        disconnectTimeoutSeconds = torrentDisconnectTimeout,
        enableDebug = enableDebug,
        enableIpv6 = enableIpv6,
        disableTcp = disableTcp,
        disableUtp = disableUtp,
        disableUpnp = disableUpnp,
        disableDht = disableDht,
        disablePex = disablePex,
        enableLpd = enableLpd,
        disableUpload = disableUpload,
        downloadRateLimitKib = downloadRateLimit,
        uploadRateLimitKib = uploadRateLimit,
        connectionsLimit = connectionsLimit,
        peersListenPort = peersListenPort,
        trackers = trackers,
    )

    fun merge(current: JsonObject, settings: EngineSettings, savePath: String): JsonObject {
        val ours = encodeWire(settings.toBTSets(savePath, parse(current)))
        return current.deepCopy().apply { ours.entrySet().forEach { (key, value) -> add(key, value) } }
    }

    fun differs(current: JsonObject, settings: EngineSettings, savePath: String): Boolean {
        val actual = parse(current)
        var wanted = settings.toBTSets(savePath, actual)
        if (!settings.useDisk) wanted = wanted.copy(torrentsSavePath = actual.torrentsSavePath)
        return wanted != actual
    }

    fun encodeWire(sets: BTSets): JsonObject = gson.toJsonTree(sets).asJsonObject

    fun parse(json: JsonObject): BTSets = gson.fromJson(json, BTSets::class.java) ?: BTSets()

    fun encode(settings: EngineSettings): String = gson.toJson(settings)

    fun decode(json: String?): EngineSettings {
        if (json.isNullOrBlank()) return EngineSettings()
        val decoded = try {
            gson.fromJson(json, EngineSettings::class.java)
        } catch (e: JsonParseException) {
            null
        } ?: return EngineSettings()

        @Suppress("SENSELESS_COMPARISON")
        val settings = if (decoded.trackers == null) decoded.copy(trackers = EngineSource.DEFAULT_TRACKERS) else decoded
        return settings.takeIf { it.errors().isEmpty() } ?: EngineSettings()
    }
}
