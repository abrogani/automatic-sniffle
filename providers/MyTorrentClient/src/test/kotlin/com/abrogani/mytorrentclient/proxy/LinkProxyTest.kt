package com.abrogani.mytorrentclient.proxy

import com.abrogani.mytorrentclient.engine.TorrentBackend
import com.abrogani.mytorrentclient.link.ProxyUrl
import com.abrogani.mytorrentclient.link.TorrentFile
import com.abrogani.mytorrentclient.link.TorrentLink
import com.abrogani.mytorrentclient.link.TorrentLinkRewriter
import com.flixclusive.core.util.log.LogRule
import com.flixclusive.model.media.common.tv.Episode
import com.flixclusive.model.provider.link.Stream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import strikt.api.expectThat
import strikt.api.expectThrows
import strikt.assertions.all
import strikt.assertions.contains
import strikt.assertions.isEqualTo
import strikt.assertions.isLessThan
import strikt.assertions.isNotNull
import strikt.assertions.isNull
import strikt.assertions.isTrue
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LinkProxyTest {
    @get:Rule
    val logRule = LogRule()

    private val hex = "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"
    private val torrentHash = "0123456789abcdef0123456789abcdef01234567"
    private val enginePort = 41234
    private val magnet = "magnet:?xt=urn:btih:$hex&dn=Show+S01&tr=udp%3A%2F%2Ft.example%3A80"
    private val engineMagnet = "magnet%3A%3Fxt%3Durn%3Abtih%3A$hex%26dn%3DShow%252BS01%26tr%3Dudp%25253A%25252F%25252Ft.example%25253A80"

    private val seasonPack =
        listOf(
            TorrentFile(0, "Show S01/Show.S01E01.mkv", 1_000),
            TorrentFile(1, "Show S01/Show.S01E02.mkv", 2_000),
            TorrentFile(2, "Show S01/Sample/show.sample.mkv", 10),
            TorrentFile(3, "Show S01/info.nfo", 5_000),
        )

    private class FakeBackend(
        @Volatile var port: Int? = 41234,
        @Volatile var startError: Throwable? = null,
        @Volatile var hash: String? = null,
        @Volatile var files: suspend (TorrentLink) -> List<TorrentFile>,
    ) : TorrentBackend {
        val starts = AtomicInteger()
        val fileCalls = AtomicInteger()
        val hashCalls = AtomicInteger()

        override suspend fun ensureRunning(): Int? {
            starts.incrementAndGet()
            startError?.let { throw it }
            return port
        }

        override suspend fun files(link: TorrentLink): List<TorrentFile> {
            fileCalls.incrementAndGet()
            return files.invoke(link)
        }

        override suspend fun infoHash(link: TorrentLink): String? {
            hashCalls.incrementAndGet()
            return hash
        }
    }

    private data class Reply(val status: Int, val headers: Map<String, String>, val body: String)

    private val proxies = mutableListOf<LinkProxy>()

    @After
    fun tearDown() = proxies.forEach { it.stop() }

    private fun proxy(backend: TorrentBackend, timeout: Duration = 5.seconds, readTimeoutMillis: Int = 5_000) =
        LinkProxy(backend, resolveTimeout = timeout, readTimeoutMillis = readTimeoutMillis).also { proxies += it }

    private fun started(backend: TorrentBackend, timeout: Duration = 5.seconds): Int =
        runBlocking { proxy(backend, timeout).start(0)!! }

    private fun raw(port: Int, request: String): String =
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 2_000)
            socket.soTimeout = 15_000
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            val out = ByteArrayOutputStream()
            socket.getInputStream().copyTo(out)
            out.toString("UTF-8")
        }

    private fun request(port: Int, target: String, method: String = "GET"): Reply {
        val text = raw(port, "$method $target HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nRange: bytes=0-\r\n\r\n")
        val head = text.substringBefore("\r\n\r\n").split("\r\n")
        return Reply(
            status = head.first().split(' ')[1].toInt(),
            headers = head.drop(1).associate { it.substringBefore(": ") to it.substringAfter(": ") },
            body = text.substringAfter("\r\n\r\n"),
        )
    }

    private fun target(source: String = magnet, season: Int? = null, episode: Int? = null): String {
        val ep = if (season != null && episode != null) Episode(id = "e", number = episode, season = season, isReleased = true) else null
        val url = TorrentLinkRewriter({ 1 }, { true }).rewrite(Stream(name = "n", url = source), ep)!!.url
        return url.substringAfter("127.0.0.1:1")
    }

    private fun location(index: Int, name: String, link: String = engineMagnet) =
        "http://127.0.0.1:$enginePort/stream/$name?link=$link&index=$index&play"

    @Test
    fun `ping answers the fixed signature`() {
        val port = started(FakeBackend(files = { seasonPack }))

        val reply = request(port, LinkProxy.PING_PATH)

        expectThat(reply.status).isEqualTo(200)
        expectThat(reply.body).isEqualTo(LinkProxy.SIGNATURE)
        expectThat(reply.headers["Connection"]).isEqualTo("close")
    }

    @Test
    fun `movie request redirects to the largest video on the engine`() {
        val backend = FakeBackend(files = { seasonPack })
        val port = started(backend)

        val reply = request(port, target())

        expectThat(reply.status).isEqualTo(302)
        expectThat(reply.headers["Location"]).isEqualTo(location(1, "Show.S01E02.mkv"))
        expectThat(reply.headers["Cache-Control"]).isEqualTo("no-store")
        expectThat(reply.body).contains("Redirecting")
    }

    @Test
    fun `episode request redirects to the matching file, including file 0`() {
        val port = started(FakeBackend(files = { seasonPack }))

        expectThat(request(port, target(season = 1, episode = 1)).headers["Location"]).isEqualTo(location(0, "Show.S01E01.mkv"))
        expectThat(request(port, target(season = 1, episode = 2)).headers["Location"]).isEqualTo(location(1, "Show.S01E02.mkv"))
    }

    @Test
    fun `HEAD gets the same redirect without a body`() {
        val port = started(FakeBackend(files = { seasonPack }))

        val reply = request(port, target(season = 1, episode = 2), method = "HEAD")

        expectThat(reply.status).isEqualTo(302)
        expectThat(reply.headers["Location"]).isEqualTo(location(1, "Show.S01E02.mkv"))
        expectThat(reply.body).isEqualTo("")
        expectThat(reply.headers["Content-Length"]?.toInt() ?: 0).isEqualTo("Redirecting to ${location(1, "Show.S01E02.mkv")}".length)
    }

    @Test
    fun `no matching episode is a 404 rather than a guess`() {
        val port = started(FakeBackend(files = { seasonPack }))

        val reply = request(port, target(season = 2, episode = 1))

        expectThat(reply.status).isEqualTo(404)
        expectThat(reply.body).isEqualTo("No video for S2E1 in this torrent")
    }

    @Test
    fun `engine not installed is a 503`() {
        val backend = FakeBackend(port = null, files = { error("must not list") })
        val port = started(backend)

        val reply = request(port, target())

        expectThat(reply.status).isEqualTo(503)
        expectThat(reply.body).contains("isn't installed")
        expectThat(backend.fileCalls.get()).isEqualTo(0)
    }

    @Test
    fun `engine start failure is a 502 with its message`() {
        val port = started(FakeBackend(startError = IllegalStateException("Restart the app to try again."), files = { seasonPack }))

        val reply = request(port, target())

        expectThat(reply.status).isEqualTo(502)
        expectThat(reply.body).isEqualTo("Restart the app to try again.")
    }

    @Test
    fun `listing failure is a 502 and is not cached`() {
        val calls = AtomicInteger()
        val backend = FakeBackend(files = { if (calls.incrementAndGet() == 1) throw IOException("HTTP 500") else seasonPack })
        val port = started(backend)

        val first = request(port, target())
        val second = request(port, target())

        expectThat(first.status).isEqualTo(502)
        expectThat(first.body).contains("HTTP 500")
        expectThat(second.status).isEqualTo(302)
        expectThat(backend.fileCalls.get()).isEqualTo(2)
    }

    @Test
    fun `metadata timeout is a 502 and cancels the listing`() {
        val cancelled = AtomicInteger()
        val backend =
            FakeBackend(
                files = {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.incrementAndGet()
                    }
                },
            )
        val port = started(backend, timeout = 300.milliseconds)

        val reply = request(port, target())

        expectThat(reply.status).isEqualTo(502)
        expectThat(reply.body).isEqualTo("Timed out waiting for the torrent's metadata")
        expectThat(cancelled.get()).isEqualTo(1)
    }

    @Test
    fun `bad requests`() {
        val port = started(FakeBackend(files = { seasonPack }))
        val encoded = URLEncoder.encode(magnet, "UTF-8")

        expectThat(request(port, "/torrent/x").status).isEqualTo(400)
        expectThat(request(port, "/torrent/x?link=https%3A%2F%2Fcdn%2Fa.mp4").status).isEqualTo(400)
        expectThat(request(port, "/torrent/x?link=$encoded&s=1").status).isEqualTo(400)
        expectThat(request(port, "/torrent/x?link=$encoded&s=a&e=1").status).isEqualTo(400)
        expectThat(request(port, "/elsewhere").status).isEqualTo(404)
        expectThat(request(port, "/torrent/x?link=$encoded", method = "POST").let { it.status to it.headers["Allow"] })
            .isEqualTo(405 to "GET, HEAD")
        expectThat(raw(port, "NONSENSE\r\n\r\n")).contains("HTTP/1.1 400 Bad Request")
        val prefix = "GET / HTTP/1.1\r\nX: "
        expectThat(raw(port, prefix + "a".repeat(16 * 1024 + 1 - prefix.length))).contains("HTTP/1.1 431 ")
    }

    @Test
    fun `absolute-form targets are accepted`() {
        val port = started(FakeBackend(files = { seasonPack }))

        expectThat(request(port, "http://127.0.0.1:$port${target()}").status).isEqualTo(302)
    }

    @Test
    fun `a torrent file is listed once and then addressed by its info hash`() {
        val backend = FakeBackend(hash = torrentHash.uppercase(), files = { seasonPack })
        val port = started(backend)
        val source = "https://t.example/dl/pack.torrent?passkey=x"

        val replies = (1..3).map { request(port, target(source, 1, it.coerceAtMost(2))) }

        expectThat(replies.map { it.status }).all { isEqualTo(302) }
        expectThat(replies.first().headers["Location"]).isEqualTo(
            location(0, "Show.S01E01.mkv", "magnet%3A%3Fxt%3Durn%3Abtih%3A$torrentHash"),
        )
        expectThat(backend.fileCalls.get()).isEqualTo(1)
        expectThat(backend.hashCalls.get()).isEqualTo(1)
    }

    @Test
    fun `a torrent file whose hash the engine can't report is a 502`() {
        val port = started(FakeBackend(hash = null, files = { seasonPack }))

        expectThat(request(port, target("https://t.example/a.torrent")).status).isEqualTo(502)
    }

    @Test
    fun `magnet metadata is cached per info hash`() {
        val backend = FakeBackend(files = { seasonPack })
        val port = started(backend)

        request(port, target(season = 1, episode = 1))
        request(port, target("magnet:?xt=urn:btih:${hex.uppercase()}", 1, 2))

        expectThat(backend.fileCalls.get()).isEqualTo(1)
        expectThat(backend.hashCalls.get()).isEqualTo(0)
    }

    @Test
    fun `concurrent requests for one torrent share a single listing`() {
        val backend =
            FakeBackend(
                files = {
                    delay(300)
                    seasonPack
                },
            )
        val port = started(backend)

        val replies =
            runBlocking(Dispatchers.IO) {
                (1..20).map { i -> async { request(port, target(season = 1, episode = 1 + i % 2)) } }.awaitAll()
            }

        expectThat(replies.map { it.status }).all { isEqualTo(302) }
        expectThat(backend.fileCalls.get()).isEqualTo(1)
    }

    @Test
    fun `slow clients and slow torrents don't block other requests`() {
        val slowHash = "%040x".format(7)
        val backend =
            FakeBackend(
                files = { link ->
                    if (link.infoHash == slowHash) awaitCancellation()
                    seasonPack
                },
            )
        val port = started(backend, timeout = 30.seconds)

        val idle = Socket(InetAddress.getByName("127.0.0.1"), port)
        val half = Socket(InetAddress.getByName("127.0.0.1"), port).apply { getOutputStream().write("GET /torrent/x?li".toByteArray()) }
        val slow = thread { runCatching { request(port, target("magnet:?xt=urn:btih:$slowHash")) } }
        try {
            val started = System.nanoTime()
            expectThat(request(port, LinkProxy.PING_PATH).status).isEqualTo(200)
            expectThat(request(port, target()).status).isEqualTo(302)
            expectThat((System.nanoTime() - started) / 1_000_000).isLessThan(2_000)
        } finally {
            idle.close()
            half.close()
            proxies.forEach { it.stop() }
            slow.join(5_000)
        }
        expectThat(slow.isAlive).isEqualTo(false)
    }

    @Test
    fun `a client that never finishes its request is dropped after the read timeout`() {
        val p = proxy(FakeBackend(files = { seasonPack }), readTimeoutMillis = 200)
        val port = runBlocking { p.start(0)!! }

        Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write("GET / HTTP/1.1\r\n".toByteArray())
            expectThat(socket.getInputStream().read()).isEqualTo(-1)
        }
    }

    @Test
    fun `stop closes the listener`() {
        val p = proxy(FakeBackend(files = { seasonPack }))
        val port = runBlocking { p.start(0)!! }
        expectThat(p.port).isEqualTo(port)

        p.stop()

        expectThat(p.port).isNull()
        expectThrows<IOException> { Socket(InetAddress.getByName("127.0.0.1"), port).close() }
        p.stop()
    }

    @Test
    fun `start is idempotent and can restart after stop`() {
        val p = proxy(FakeBackend(files = { seasonPack }))
        runBlocking {
            val first = p.start(0)!!
            expectThat(p.start(0)).isEqualTo(first)
            expectThat(p.start(first)).isEqualTo(first)
            p.stop()
            val again = p.start(0)
            expectThat(again).isNotNull()
            expectThat(request(again!!, LinkProxy.PING_PATH).status).isEqualTo(200)
        }
    }

    @Test
    fun `a busy port held by a previous copy is reused`() {
        val port = started(FakeBackend(files = { seasonPack }))
        val second = proxy(FakeBackend(files = { error("unused") }))

        expectThat(runBlocking { second.start(port) }).isEqualTo(port)
        expectThat(second.port).isEqualTo(port)

        second.stop()
        expectThat(second.port).isNull()
        expectThat(request(port, LinkProxy.PING_PATH).status).isEqualTo(200)
    }

    @Test
    fun `a busy port held by another app is refused`() {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { foreign ->
            val server =
                thread(isDaemon = true) {
                    runCatching {
                        while (true) {
                            foreign.accept().use { s ->
                                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nnope".toByteArray())
                            }
                        }
                    }
                }
            val p = proxy(FakeBackend(files = { seasonPack }))

            expectThat(runBlocking { p.start(foreign.localPort) }).isNull()
            expectThat(p.port).isNull()
            server.interrupt()
        }
    }

    @Test
    fun `binds loopback only`() {
        val p = proxy(FakeBackend(files = { seasonPack }))
        val port = runBlocking { p.start(0)!! }
        val external =
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?: return

        val reachable = runCatching { Socket().use { it.connect(InetSocketAddress(external, port), 1_000) } }.isSuccess
        expectThat(reachable).isEqualTo(false)
    }

    @Test
    fun `answers on the ipv4 loopback the rewritten links point at`() {
        val p = proxy(FakeBackend(files = { seasonPack }))
        val port = runBlocking { p.start(0)!! }

        val reachable = runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1_000) }
        }.isSuccess
        expectThat(reachable).isTrue()
    }

    @Test
    fun `rewriter urls round trip through the proxy parser`() {
        val path = target(season = 4, episode = 9)

        expectThat(path.startsWith(ProxyUrl.PATH_PREFIX)).isTrue()
        expectThat(ProxyUrl.parseQuery(path.substringAfter('?'))?.let { it.season to it.episode }).isEqualTo(4 to 9)
    }
}
