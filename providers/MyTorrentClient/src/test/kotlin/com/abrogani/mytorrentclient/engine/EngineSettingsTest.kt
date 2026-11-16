package com.abrogani.mytorrentclient.engine

import com.abrogani.mytorrentclient.engine.EngineSettingsCodec.toBTSets
import com.abrogani.mytorrentclient.engine.EngineSettingsCodec.toEngineSettings
import com.google.gson.JsonParser
import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.hasSize
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isTrue

class EngineSettingsTest {
    private val savePath = "/data/cache/torrent/cache"

    private val engineDefaults = """
        {"CacheSize":67108864,"ReaderReadAHead":95,"PreloadCache":50,"UseDisk":false,"TorrentsSavePath":"",
         "RemoveCacheOnDrop":false,"ForceEncrypt":false,"RetrackersMode":1,"TorrentDisconnectTimeout":30,
         "EnableDebug":false,"EnableDLNA":false,"FriendlyName":"","EnableIPv6":false,"DisableTCP":false,
         "DisableUTP":false,"DisableUPNP":false,"DisableDHT":false,"DisablePEX":false,"DisableUpload":false,
         "DownloadRateLimit":0,"UploadRateLimit":0,"ConnectionsLimit":25,"PeersListenPort":0}
    """.trimIndent()

    @Test
    fun `defaults are valid and match the engine's defaults`() {
        expectThat(EngineSettings().errors()).isEmpty()

        val current = JsonParser.parseString(engineDefaults).asJsonObject
        expectThat(EngineSettingsCodec.parse(current).toEngineSettings()).isEqualTo(EngineSettings())
        expectThat(EngineSettingsCodec.differs(current, EngineSettings(), savePath)).isFalse()
    }

    @Test
    fun `wire model has all 23 PascalCase fields`() {
        val json = EngineSettingsCodec.encodeWire(BTSets())

        expectThat(json.keySet()).hasSize(23)
        expectThat(json.keySet()).containsExactlyInAnyOrder(
            JsonParser.parseString(engineDefaults).asJsonObject.keySet()
        )
    }

    @Test
    fun `units map to the engine's`() {
        val settings = EngineSettings(
            cacheSizeMb = 256,
            downloadRateLimitKib = 1500,
            uploadRateLimitKib = 64,
            useDisk = true,
            peersListenPort = 6881,
            disconnectTimeoutSeconds = 90,
        )

        val sets = settings.toBTSets(savePath)
        expectThat(sets.cacheSize).isEqualTo(256L * 1024 * 1024)
        expectThat(sets.downloadRateLimit).isEqualTo(1500)
        expectThat(sets.uploadRateLimit).isEqualTo(64)
        expectThat(sets.useDisk).isTrue()
        expectThat(sets.torrentsSavePath).isEqualTo(savePath)
        expectThat(sets.peersListenPort).isEqualTo(6881)
        expectThat(sets.torrentDisconnectTimeout).isEqualTo(90)

        expectThat(sets.toEngineSettings(settings.trackers)).isEqualTo(settings)
    }

    @Test
    fun `every flag round-trips`() {
        val settings = EngineSettings(
            readAheadPercent = 50, preloadPercent = 0, removeCacheOnDrop = true, forceEncrypt = true,
            retrackersMode = 3, enableDebug = true, enableIpv6 = true, disableTcp = true, disableUpnp = true,
            disableDht = true, disablePex = true, disableUpload = true, connectionsLimit = 100,
        )

        expectThat(settings.toBTSets(savePath).toEngineSettings()).isEqualTo(settings)
    }

    @Test
    fun `merge keeps hidden and unknown fields`() {
        val current = JsonParser.parseString(
            engineDefaults.replace("\"EnableDLNA\":false", "\"EnableDLNA\":true")
                .replace("\"FriendlyName\":\"\"", "\"FriendlyName\":\"Living room\"")
                .replace("{", "{\"FutureField\":\"kept\",")
        ).asJsonObject

        val merged = EngineSettingsCodec.merge(current, EngineSettings(cacheSizeMb = 128, disableUtp = true), savePath)

        expectThat(merged["EnableDLNA"].asBoolean).isTrue()
        expectThat(merged["FriendlyName"].asString).isEqualTo("Living room")
        expectThat(merged["FutureField"].asString).isEqualTo("kept")
        expectThat(merged["CacheSize"].asLong).isEqualTo(128L * 1024 * 1024)
        expectThat(merged["DisableUTP"].asBoolean).isTrue()
        expectThat(merged["TorrentsSavePath"].asString).isEqualTo(savePath)
        expectThat(merged.keySet()).hasSize(24)
        expectThat(current["CacheSize"].asLong).isEqualTo(64L * 1024 * 1024)
    }

    @Test
    fun `differs only when something we manage changed`() {
        val current = JsonParser.parseString(engineDefaults).asJsonObject

        expectThat(EngineSettingsCodec.differs(current, EngineSettings(connectionsLimit = 50), savePath)).isTrue()
        expectThat(EngineSettingsCodec.differs(current, EngineSettings(useDisk = true), savePath)).isTrue()
        expectThat(EngineSettingsCodec.differs(current, EngineSettings(trackers = emptyList()), savePath)).isFalse()

        val onDisk = EngineSettingsCodec.merge(current, EngineSettings(useDisk = true), savePath)
        expectThat(EngineSettingsCodec.differs(onDisk, EngineSettings(useDisk = true), savePath)).isFalse()
        expectThat(EngineSettingsCodec.differs(onDisk, EngineSettings(useDisk = true), "/elsewhere")).isTrue()
    }

    @Test
    fun `validation reports each problem`() {
        val errors = EngineSettings(
            cacheSizeMb = 16,
            readAheadPercent = 4,
            preloadPercent = 101,
            retrackersMode = 4,
            disconnectTimeoutSeconds = 0,
            disableTcp = true,
            disableUtp = true,
            downloadRateLimitKib = -1,
            uploadRateLimitKib = -1,
            connectionsLimit = 0,
            peersListenPort = 80,
            trackers = listOf("udp://ok.example:1337/announce", "not a tracker"),
        ).errors()

        expectThat(errors).hasSize(11)
        expectThat(errors).contains("Invalid tracker: not a tracker")
        expectThat(errors).contains("TCP and uTP can't both be disabled.")
    }

    @Test
    fun `boundaries are valid`() {
        listOf(
            EngineSettings(cacheSizeMb = 32), EngineSettings(cacheSizeMb = 2048),
            EngineSettings(readAheadPercent = 5), EngineSettings(readAheadPercent = 100),
            EngineSettings(preloadPercent = 0), EngineSettings(disconnectTimeoutSeconds = 3600),
            EngineSettings(connectionsLimit = 1), EngineSettings(connectionsLimit = 200),
            EngineSettings(peersListenPort = 1024), EngineSettings(peersListenPort = 65535),
            EngineSettings(retrackersMode = 0), EngineSettings(trackers = emptyList()),
            EngineSettings(trackers = listOf("wss://tracker.example/announce", "https://t.example/announce")),
        ).forEach { expectThat(it.errors()).isEmpty() }
    }

    @Test
    fun `persisted JSON round-trips`() {
        val settings = EngineSettings(cacheSizeMb = 512, useDisk = true, trackers = listOf("udp://a.example:1/announce"))

        expectThat(EngineSettingsCodec.decode(EngineSettingsCodec.encode(settings))).isEqualTo(settings)
    }

    @Test
    fun `missing, partial, broken or invalid JSON falls back to defaults`() {
        expectThat(EngineSettingsCodec.decode(null)).isEqualTo(EngineSettings())
        expectThat(EngineSettingsCodec.decode("")).isEqualTo(EngineSettings())
        expectThat(EngineSettingsCodec.decode("{not json")).isEqualTo(EngineSettings())
        expectThat(EngineSettingsCodec.decode("""{"cacheSizeMb":1}""")).isEqualTo(EngineSettings())
        expectThat(EngineSettingsCodec.decode("""{"cacheSizeMb":128}""")).isEqualTo(EngineSettings(cacheSizeMb = 128))
        expectThat(EngineSettingsCodec.decode("""{"trackers":null}""")).isEqualTo(EngineSettings())
    }
}
