package com.abrogani.servetorr.engine

import com.abrogani.servetorr.engine.TorrServerClient.Companion.toTorrentFiles
import com.abrogani.servetorr.link.TorrentFile
import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isNull
import strikt.assertions.isTrue

class EngineSourceTest {
    @Test
    fun `device ABI order wins`() {
        expectThat(EngineSource.selectAbi(listOf("x86_64", "x86", "arm64-v8a"))).isEqualTo("x86_64")
        expectThat(EngineSource.selectAbi(listOf("arm64-v8a", "armeabi-v7a", "armeabi"))).isEqualTo("arm64-v8a")
    }

    @Test
    fun `unsupported ABIs are skipped`() {
        expectThat(EngineSource.selectAbi(listOf("armeabi", "armeabi-v7a"))).isEqualTo("armeabi-v7a")
        expectThat(EngineSource.selectAbi(listOf("mips", "armeabi"))).isNull()
        expectThat(EngineSource.selectAbi(emptyList())).isNull()
    }

    @Test
    fun `entry name points into the AAR's jni folder`() {
        expectThat(EngineSource.entryName("arm64-v8a")).isEqualTo("jni/arm64-v8a/libgojni.so")
    }

    @Test
    fun `engine is the ABI's library plus the shared bindings dex`() {
        val files = EngineSource.engineFiles("x86_64")!!

        expectThat(files.map { it.entryName }).containsExactly("jni/x86_64/libgojni.so", "classes.dex")
        expectThat(files.map { it.fileName }).containsExactly("libgojni.so", "bindings.dex")
        expectThat(files.map { it.sha256 })
            .containsExactly(EngineSource.LIBRARY_SHA256.getValue("x86_64"), EngineSource.DEX_SHA256)
        expectThat(EngineSource.engineFiles("mips")).isNull()
    }

    @Test
    fun `status file stats map to torrent files`() {
        val status = TorrServerClient.parseStatus(
            """
            {"title":"Show","hash":"08ada5a7a6183aae1e09d831df6748d566095a10","stat":3,"stat_string":"Torrent working",
             "file_stats":[
               {"path":"Show/S01E00.mkv","length":10},
               {"id":1,"path":"Show/S01E01.mkv","length":734003200},
               {"id":2,"path":"Show/sample.txt","length":12},
               {"id":3,"path":"","length":5},
               {"id":4,"length":5}
             ]}
            """.trimIndent()
        )!!

        expectThat(status.hash).isEqualTo("08ada5a7a6183aae1e09d831df6748d566095a10")
        expectThat(status.toTorrentFiles()).containsExactly(
            TorrentFile(id = 1, path = "Show/S01E01.mkv", length = 734003200),
            TorrentFile(id = 2, path = "Show/sample.txt", length = 12),
        )
    }

    @Test
    fun `entries without an id are skipped, ids are passed through as the engine numbers them`() {
        val status = TorrServerClient.parseStatus("""{"file_stats":[{"path":"no-id.mkv","length":7},{"id":1,"path":"movie.mkv","length":9}]}""")!!

        expectThat(status.toTorrentFiles()).containsExactly(TorrentFile(1, "movie.mkv", 9))
    }

    @Test
    fun `process bitness picks the ABI list`() {
        val abis64 = listOf("arm64-v8a")
        val abis32 = listOf("armeabi-v7a", "armeabi")

        expectThat(EngineSource.processAbis(true, abis64, abis32)).isEqualTo(abis64)
        expectThat(EngineSource.selectAbi(EngineSource.processAbis(false, abis64, abis32))).isEqualTo("armeabi-v7a")
    }

    @Test
    fun `release tag and echo follow the engine version`() {
        expectThat(EngineSource.VERSION.toIntOrNull()).isEqualTo(1)
        expectThat(EngineSource.AAR_URL)
            .isEqualTo("https://github.com/abrogani/servetorr-engine/releases/download/v1/torrserver.aar")
        expectThat(TorrServerClient.ECHO_SIGNATURE).isEqualTo("ServeTorr torrserver 1")
    }

    @Test
    fun `echo must be our build's exact signature`() {
        expectThat(TorrServerClient.isEngineEcho("ServeTorr torrserver 1")).isTrue()
        expectThat(TorrServerClient.isEngineEcho("ServeTorr torrserver 1\n")).isTrue()
        expectThat(TorrServerClient.isEngineEcho("ServeTorr torrserver st-1")).isFalse()
        expectThat(TorrServerClient.isEngineEcho("ServeTorr torrserver 12")).isFalse()
        expectThat(TorrServerClient.isEngineEcho("CloudStream torrserver")).isFalse()
        expectThat(TorrServerClient.isEngineEcho("MatriX.136")).isFalse()
        expectThat(TorrServerClient.isEngineEcho("")).isFalse()
        expectThat(TorrServerClient.isEngineEcho(null)).isFalse()
    }

    @Test
    fun `status without metadata has no files`() {
        val status = TorrServerClient.parseStatus("""{"hash":"abc","stat":1,"stat_string":"Torrent info"}""")!!

        expectThat(status.toTorrentFiles()).isEmpty()
    }

    @Test
    fun `omitted length defaults to zero`() {
        val status = TorrServerClient.parseStatus("""{"file_stats":[{"id":4,"path":"a.mp4"}]}""")!!

        expectThat(status.toTorrentFiles()).containsExactly(TorrentFile(4, "a.mp4", 0))
    }
}
