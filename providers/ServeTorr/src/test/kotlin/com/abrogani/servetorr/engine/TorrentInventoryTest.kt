package com.abrogani.servetorr.engine

import com.abrogani.servetorr.engine.TorrServerClient.TorrentStatus
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isNull
import strikt.assertions.isTrue
import java.io.File

class TorrentInventoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val hashA = "08ada5a7a6183aae1e09d831df6748d566095a10"
    private val hashB = "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"
    private val hashC = "c9e15763f722f23e98a29decdfae341b98d53056"

    private fun File.write(path: String, bytes: Int): File =
        File(this, path).also {
            it.parentFile.mkdirs()
            it.writeBytes(ByteArray(bytes))
        }

    @Test
    fun `parses upstream status json`() {
        val status = TorrServerClient.parseStatus(
            """{"title":"Sintel","category":"","poster":"","timestamp":1,"name":"Sintel","hash":"${hashA.uppercase()}",
            "stat":3,"stat_string":"Torrent working","loaded_size":1048576,"torrent_size":129241752,
            "download_speed":2048.7,"upload_speed":12.2,"total_peers":40,"active_peers":7,"connected_seeders":5}"""
        )!!
        val info = TorrentInventory.toInfo(status, 300L)!!
        expectThat(info).isEqualTo(
            TorrentInfo(
                hash = hashA,
                title = "Sintel",
                sizeBytes = 129241752,
                status = "Streaming",
                active = true,
                peers = 7,
                seeders = 5,
                downloadBytesPerSecond = 2048,
                uploadBytesPerSecond = 12,
                cachedBytes = 300L,
            )
        )
    }

    @Test
    fun `omitted zero counters are zero for active torrents`() {
        val info = TorrentInventory.toInfo(TorrentStatus(hash = hashA, name = "n", stat = 3), null)!!
        expectThat(info.status).isEqualTo("Idle")
        expectThat(info.peers).isEqualTo(0)
        expectThat(info.downloadBytesPerSecond).isEqualTo(0L)
        expectThat(info.sizeBytes).isNull()
    }

    @Test
    fun `saved torrents are inactive with unknown counters`() {
        val info = TorrentInventory.toInfo(TorrentStatus(hash = hashA, title = "", name = "", stat = 5), null)!!
        expectThat(info.active).isFalse()
        expectThat(info.status).isEqualTo("Saved")
        expectThat(info.title).isEqualTo(hashA)
        expectThat(info.peers).isNull()
        expectThat(info.uploadBytesPerSecond).isNull()
    }

    @Test
    fun `maps every upstream stat`() {
        expectThat((0..6).map { TorrentInventory.statusLabel(it, null, null) }).containsExactly(
            "Starting", "Getting info", "Preloading", "Idle", "Closed", "Saved", "Unknown",
        )
        expectThat((0..5).map { TorrentInventory.isActive(it) })
            .containsExactly(true, true, true, true, false, false)
        expectThat(TorrentInventory.isActive(null)).isTrue()
    }

    @Test
    fun `rejects statuses without a valid hash`() {
        expectThat(TorrentInventory.toInfo(TorrentStatus(hash = "nope", stat = 3), null)).isNull()
        expectThat(TorrentInventory.toInfo(TorrentStatus(hash = null, stat = 3), null)).isNull()
    }

    @Test
    fun `merges engine torrents with disk-only leftovers`() {
        val merged = TorrentInventory.merge(
            listOf(
                TorrentStatus(hash = hashB, title = "Saved", stat = 5),
                TorrentStatus(hash = hashA, title = "Playing", stat = 3),
                TorrentStatus(hash = hashA, title = "Dup", stat = 3),
            ),
            mapOf(hashA to 10L, hashC to 50L, "1111111111111111111111111111111111111111" to 90L),
        )
        expectThat(merged.map { it.hash }).containsExactly(
            hashA, hashB, "1111111111111111111111111111111111111111", hashC,
        )
        expectThat(merged[0].cachedBytes).isEqualTo(10L)
        expectThat(merged[1].cachedBytes).isNull()
        expectThat(merged[3].status).isEqualTo(TorrentInventory.DISK_ONLY_STATUS)
        expectThat(merged[3].active).isFalse()
        expectThat(merged[3].cachedBytes).isEqualTo(50L)
    }

    @Test
    fun `aggregates disk cache sizes per info hash`() {
        val cache = temp.newFolder("cache")
        cache.write("$hashA/0", 100)
        cache.write("$hashA/1", 50)
        cache.write("${hashB.uppercase()}/7", 30)
        cache.write("${hashB.uppercase()}/8", 5)
        cache.write("not-a-hash/0", 999)
        cache.write("$hashC", 999)

        expectThat(TorrentInventory.diskCache(cache)).isEqualTo(mapOf(hashA to 150L, hashB to 35L))
        expectThat(TorrentInventory.cacheDirsOf(cache, hashB).map { it.name })
            .containsExactly(hashB.uppercase())
        expectThat(TorrentInventory.diskCache(File(cache, "missing"))).isEqualTo(emptyMap())
        expectThat(TorrentInventory.sizeOf(cache)).isEqualTo(100L + 50 + 30 + 5 + 999 + 999)
    }

    private fun populated(): EngineLayout {
        val files = temp.newFolder("files")
        val cache = temp.newFolder("cachedir")
        val layout = EngineLayout(files, cache, "1")
        files.write("torrent/engine/1/libgojni.so", 10)
        files.write("torrent/engine/1/bindings.dex", 10)
        files.write("torrent/engine/1/libgojni.so.tmp", 3)
        files.write("torrent/engine/1/oat/arm64/bindings.odex", 4)
        files.write("torrent/engine/st-1/libgojni.so", 10)
        files.write("torrent/engine/7861970/libgojni.so", 10)
        files.write("torrent/stray.bin", 1)
        cache.write("torrent/cache/$hashA/0", 100)
        cache.write("torrent/server/config.db", 20)
        cache.write("torrent/server/settings.json", 2)
        cache.write("torrent/server/viewed.json", 2)
        cache.write("torrent/server/torrserver.db", 2)
        cache.write("torrent/old/thing", 2)
        return layout
    }

    private fun EngineLayout.relative(files: List<File>): List<String> =
        files.map { file ->
            listOf(filesRoot, cacheRoot).firstNotNullOf { root ->
                file.relativeToOrNull(root)?.takeUnless { it.path.startsWith("..") }?.let { "${root.parentFile.name}:${it.path}" }
            }
        }

    @Test
    fun `stale files keep the current engine and everything else goes when stopped`() {
        val layout = populated()
        expectThat(layout.relative(TorrentInventory.staleFiles(layout, engineHoldsData = false))).containsExactlyInAnyOrder(
            "files:stray.bin",
            "files:engine/st-1",
            "files:engine/7861970",
            "files:engine/1/libgojni.so.tmp",
            "cachedir:old",
            "cachedir:cache/$hashA",
            "cachedir:server/config.db",
            "cachedir:server/settings.json",
            "cachedir:server/viewed.json",
            "cachedir:server/torrserver.db",
        )
    }

    @Test
    fun `stale files keep the open engine databases while it runs`() {
        val layout = populated()
        expectThat(layout.relative(TorrentInventory.staleFiles(layout, engineHoldsData = true))).containsExactlyInAnyOrder(
            "files:stray.bin",
            "files:engine/st-1",
            "files:engine/7861970",
            "files:engine/1/libgojni.so.tmp",
            "cachedir:old",
            "cachedir:cache/$hashA",
            "cachedir:server/torrserver.db",
        )
    }

    @Test
    fun `stale files keep markers, the lock and newer engine installs`() {
        val layout = populated()
        val files = layout.filesRoot.parentFile
        files.write("torrent/${EngineUpdate.WANTED_MARKER}", 1)
        files.write("torrent/${EngineUpdate.LOCK_FILE}", 0)
        files.write("torrent/engine/1/${EngineUpdate.PINS_MARKER}", 64)
        files.write("torrent/engine/2/libgojni.so", 10)
        files.write("torrent/engine/2/bindings.dex", 10)
        expectThat(layout.relative(TorrentInventory.staleFiles(layout, engineHoldsData = true))).containsExactlyInAnyOrder(
            "files:stray.bin",
            "files:engine/st-1",
            "files:engine/7861970",
            "files:engine/1/libgojni.so.tmp",
            "cachedir:old",
            "cachedir:cache/$hashA",
            "cachedir:server/torrserver.db",
        )
    }

    @Test
    fun `stale files on an empty layout`() {
        val layout = EngineLayout(File(temp.root, "a"), File(temp.root, "b"), "1")
        expectThat(TorrentInventory.staleFiles(layout, engineHoldsData = false)).isEqualTo(emptyList())
    }

    @Test
    fun `describes missing engine files with paths and sizes`() {
        val layout = populated()
        layout.libraryFile.delete()
        val text = TorrentInventory.describeEngineFiles(layout)
        expectThat(text.contains("libgojni.so missing at ${layout.libraryFile.absolutePath}")).isTrue()
        expectThat(text.contains("bindings.dex present (10 bytes) at ${layout.dexFile.absolutePath}")).isTrue()
        expectThat(text.contains("st-1${File.separator}libgojni.so (10 bytes)")).isTrue()
    }
}
