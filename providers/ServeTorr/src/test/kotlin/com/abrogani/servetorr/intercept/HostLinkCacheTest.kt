package com.abrogani.servetorr.intercept

import com.abrogani.servetorr.link.ProxyUrl
import com.abrogani.servetorr.link.TorrentLink
import com.flixclusive.model.media.common.tv.Episode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test
import strikt.api.expectThat
import strikt.api.expectThrows
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isNull
import strikt.assertions.isSameInstanceAs
import strikt.assertions.isTrue

private const val SINTEL = "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel"

private data class FakeCachedStream(
    val url: String,
    val ownerId: String,
    val mediaId: String,
    val providerId: String = "tmdb",
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
)

private data class FakeUser(val id: String, val name: String)

private class FakeCachedMediaLinkDao(streams: List<FakeCachedStream>) {
    val rows = streams.toMutableList()

    suspend fun getStreamsByOwner(ownerId: String): List<FakeCachedStream> {
        yield()
        return rows.filter { it.ownerId == ownerId }
    }

    suspend fun deleteStream(url: String, ownerId: String) {
        yield()
        rows.removeAll { it.url == url && it.ownerId == ownerId }
    }
}

private class FakeUserDao(private val users: List<FakeUser>) {
    suspend fun getAll(): List<FakeUser> = users
}

private class FakeAppDatabase(private val links: FakeCachedMediaLinkDao, private val users: FakeUserDao) {
    fun cachedMediaLinkDao(): FakeCachedMediaLinkDao = links

    fun userDao(): FakeUserDao = users
}

private class FakeFailingDao {
    suspend fun getStreamsByOwner(ownerId: String): List<FakeCachedStream> = error("db closed")
}

private class FakeFailingDatabase {
    fun cachedMediaLinkDao(): FakeFailingDao = FakeFailingDao()

    fun userDao(): FakeUserDao = FakeUserDao(listOf(FakeUser("1", "a")))
}

private class FakeScopedProvider(provider: () -> Any) {
    @Volatile private var provider: (() -> Any)? = provider

    @Volatile private var instance: Any? = UNINITIALIZED

    fun get(): Any {
        if (instance === UNINITIALIZED) {
            instance = provider!!()
            provider = null
        }
        return instance!!
    }

    companion object {
        @JvmField
        val UNINITIALIZED = Any()
    }
}

private class FakeFactory(private val factory: () -> Any) {
    var calls = 0

    fun get(): Any {
        calls++
        return factory()
    }
}

private class FakeShard(@JvmField val provideAppDatabaseProvider: FakeScopedProvider)

private class FakeSingletonC(
    @JvmField val providesCachedMediaLinkDaoProvider: FakeFactory,
    @JvmField val singletonCImplShard: FakeShard,
)

private class FakeUnnamedComponent(
    @JvmField val a: FakeScopedProvider,
    @JvmField val b: FakeScopedProvider,
)

class HostLinkCacheTest {
    private val episode = Episode(id = "e", number = 2, season = 1, isReleased = true)

    private fun proxyUrl(port: Int, episode: Episode? = null) =
        ProxyUrl.build(port, TorrentLink.parse(SINTEL)!!, episode)

    private fun database(vararg streams: FakeCachedStream, users: List<String> = listOf("1", "2")) =
        FakeAppDatabase(FakeCachedMediaLinkDao(streams.toList()), FakeUserDao(users.map { FakeUser(it, "u$it") }))

    @Test
    fun `raw torrents are stale once the proxy is up and the engine installed`() {
        val policy = StaleLinkPolicy(currentProxyPort = 47390, purgeRawTorrents = true)

        expectThat(policy.isStale(SINTEL)).isTrue()
        expectThat(policy.isStale("08ada5a7a6183aae1e09d831df6748d566095a10")).isTrue()
        expectThat(policy.isStale("https://example.com/files/sintel.torrent")).isTrue()
        expectThat(policy.isStale("https://example.com/video.m3u8")).isFalse()
        expectThat(policy.isStale("http://127.0.0.1:8080/stream/video?link=x")).isFalse()
    }

    @Test
    fun `raw torrents stay while the engine is missing or the proxy is down`() {
        expectThat(StaleLinkPolicy(47390, purgeRawTorrents = false).isStale(SINTEL)).isFalse()
        expectThat(StaleLinkPolicy(null, purgeRawTorrents = true).isStale(SINTEL)).isFalse()
    }

    @Test
    fun `proxy urls are stale only on another port`() {
        val policy = StaleLinkPolicy(currentProxyPort = 47390, purgeRawTorrents = false)

        expectThat(policy.isStale(proxyUrl(47390))).isFalse()
        expectThat(policy.isStale(proxyUrl(47390, episode))).isFalse()
        expectThat(policy.isStale(proxyUrl(47391))).isTrue()
        expectThat(policy.isStale(proxyUrl(47391, episode))).isTrue()
        expectThat(StaleLinkPolicy(null, purgeRawTorrents = false).isStale(proxyUrl(47391))).isFalse()
    }

    @Test
    fun `a proxy url whose name ends in torrent is still a proxy url`() {
        val link = TorrentLink.parse("$SINTEL.torrent")!!
        val url = ProxyUrl.build(47390, link, null)

        expectThat(TorrentLink.parse(url)).not().isNull()
        expectThat(StaleLinkPolicy.proxyPortOf(url)).isEqualTo(47390)
        expectThat(StaleLinkPolicy(47390, purgeRawTorrents = true).isStale(url)).isFalse()
    }

    @Test
    fun `lookalike urls are not proxy urls`() {
        expectThat(StaleLinkPolicy.proxyPortOf("http://127.0.0.1:47390/torrent/x")).isNull()
        expectThat(StaleLinkPolicy.proxyPortOf("http://127.0.0.1:47390/other/x?link=$SINTEL")).isNull()
        expectThat(StaleLinkPolicy.proxyPortOf("http://10.0.0.1:47390/torrent/x?link=magnet%3A%3Fxt%3Durn%3Abtih%3A08ada5a7a6183aae1e09d831df6748d566095a10")).isNull()
        expectThat(StaleLinkPolicy.proxyPortOf("not a url")).isNull()
    }

    @Test
    fun `purges every stream of a group holding a stale link, across users`() {
        val db = database(
            FakeCachedStream(SINTEL, "1", "movie"),
            FakeCachedStream("https://cdn/movie.m3u8", "1", "movie"),
            FakeCachedStream("https://cdn/other.m3u8", "1", "other"),
            FakeCachedStream(proxyUrl(1111, episode), "2", "show", seasonNumber = 1, episodeNumber = 2),
            FakeCachedStream("https://cdn/s1e2.m3u8", "2", "show", seasonNumber = 1, episodeNumber = 2),
            FakeCachedStream("https://cdn/s1e3.m3u8", "2", "show", seasonNumber = 1, episodeNumber = 3),
            FakeCachedStream("https://cdn/s1e2-other.m3u8", "2", "show", providerId = "other", seasonNumber = 1, episodeNumber = 2),
            FakeCachedStream(proxyUrl(47390), "2", "fresh"),
        )

        val removed = runBlocking { HostLinkCache.purgeIn(db, StaleLinkPolicy(47390, purgeRawTorrents = true)) }

        expectThat(removed).isEqualTo(4)
        expectThat(db.cachedMediaLinkDao().rows.map { it.url }).containsExactlyInAnyOrder(
            "https://cdn/other.m3u8",
            "https://cdn/s1e3.m3u8",
            "https://cdn/s1e2-other.m3u8",
            proxyUrl(47390),
        )
    }

    @Test
    fun `owners that are not users are left alone`() {
        val db = database(FakeCachedStream(SINTEL, "ghost", "movie"), users = listOf("1"))

        val removed = runBlocking { HostLinkCache.purgeIn(db, StaleLinkPolicy(47390, purgeRawTorrents = true)) }

        expectThat(removed).isEqualTo(0)
        expectThat(db.cachedMediaLinkDao().rows.size).isEqualTo(1)
    }

    @Test
    fun `dao failures surface from purgeIn`() {
        expectThrows<IllegalStateException> {
            HostLinkCache.purgeIn(FakeFailingDatabase(), StaleLinkPolicy(47390, purgeRawTorrents = true))
        }
    }

    @Test
    fun `finds the database through a shard without touching factories`() {
        val db = database()
        val factory = FakeFactory { FakeCachedMediaLinkDao(emptyList()) }
        val component = FakeSingletonC(
            providesCachedMediaLinkDaoProvider = factory,
            singletonCImplShard = FakeShard(FakeScopedProvider { db }.also { it.get() }),
        )

        expectThat(HostLinkCache.findDatabaseIn(component)).isSameInstanceAs(db)
        expectThat(factory.calls).isEqualTo(0)
    }

    @Test
    fun `falls back to initialised scoped bindings when names don't match`() {
        val db = database()
        var touched = false
        val component = FakeUnnamedComponent(
            a = FakeScopedProvider {
                touched = true
                Any()
            },
            b = FakeScopedProvider { db }.also { it.get() },
        )

        expectThat(HostLinkCache.findDatabaseIn(component)).isSameInstanceAs(db)
        expectThat(touched).isFalse()
    }

    @Test
    fun `no database reachable`() {
        val component = FakeUnnamedComponent(
            a = FakeScopedProvider { Any() },
            b = FakeScopedProvider { "x" }.also { it.get() },
        )

        expectThat(HostLinkCache.findDatabaseIn(component)).isNull()
    }
}
