package com.abrogani.torrent.engine

import com.abrogani.torrent.engine.TorrServerClient.Companion.toTorrentFiles
import com.abrogani.torrent.link.TorrentFile
import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import strikt.assertions.isNull

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
    fun `status file stats map to torrent files`() {
        val status = TorrServerClient.parseStatus(
            """
            {"title":"Show","hash":"08ada5a7a6183aae1e09d831df6748d566095a10","stat":3,"stat_string":"Torrent working",
             "file_stats":[
               {"id":1,"path":"Show/S01E01.mkv","length":734003200},
               {"id":2,"path":"Show/sample.txt","length":12},
               {"id":3,"path":"","length":5},
               {"path":"no-id.mkv","length":5}
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
