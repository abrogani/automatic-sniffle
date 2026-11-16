package com.abrogani.mytorrentclient.proxy

import com.abrogani.mytorrentclient.engine.TorrentBackend
import com.abrogani.mytorrentclient.link.ProxyUrl
import com.flixclusive.core.util.coroutines.FlxDispatchers.Companion.withIOContext
import com.flixclusive.core.util.log.errorLog
import com.flixclusive.core.util.log.infoLog
import com.flixclusive.core.util.log.warnLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class LinkProxy(
    private val backend: TorrentBackend,
    private val resolveTimeout: Duration = 30.seconds,
    private val maxConnections: Int = 32,
    private val readTimeoutMillis: Int = 5_000,
) {
    private class Running(
        val server: ServerSocket,
        val scope: CoroutineScope,
        val resolver: LinkResolver,
        val permits: Semaphore,
        val sockets: MutableSet<Socket>,
    )

    private val lifecycle = Mutex()
    @Volatile private var running: Running? = null
    @Volatile private var reusedPort: Int? = null

    val port: Int?
        get() = running?.server?.localPort ?: reusedPort

    suspend fun start(port: Int): Int? =
        lifecycle.withLock {
            this.port?.let { current ->
                if (port == 0 || port == current) return@withLock current
                stop()
            }

            val server =
                withIOContext {
                    try {
                        ServerSocket().apply {
                            reuseAddress = true
                            bind(InetSocketAddress(LOOPBACK_V4, port), BACKLOG)
                        }
                    } catch (e: IOException) {
                        if (e !is BindException) warnLog("[MyTorrentClient] Couldn't open the torrent proxy on port $port: $e")
                        null
                    }
                }

            if (server == null) {
                if (withIOContext { isOurProxy(port) }) {
                    infoLog("[MyTorrentClient] Reusing the torrent proxy already listening on port $port")
                    reusedPort = port
                    return@withLock port
                }
                warnLog("[MyTorrentClient] Port $port is used by another app; torrent links won't be rewritten. Pick another port in the Torrent settings.")
                return@withLock null
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val state =
                Running(
                    server = server,
                    scope = scope,
                    resolver = LinkResolver(backend, scope, resolveTimeout),
                    permits = Semaphore(maxConnections),
                    sockets = Collections.synchronizedSet(HashSet()),
                )
            running = state
            thread(name = "Torrent-proxy-${server.localPort}", isDaemon = true) { acceptLoop(state) }
            infoLog("[MyTorrentClient] Torrent proxy listening on 127.0.0.1:${server.localPort}")
            server.localPort
        }

    fun stop() {
        reusedPort = null
        val state = running ?: return
        running = null
        close(state)
    }

    private fun close(state: Running) {
        runCatching { state.server.close() }
        state.scope.cancel()
        synchronized(state.sockets) { state.sockets.toList() }.forEach { runCatching { it.close() } }
    }

    private fun acceptLoop(state: Running) {
        while (!state.server.isClosed) {
            val socket =
                try {
                    state.server.accept()
                } catch (e: IOException) {
                    if (state.server.isClosed) break
                    warnLog("[MyTorrentClient] Torrent proxy accept failed: $e")
                    Thread.sleep(ACCEPT_BACKOFF_MILLIS)
                    continue
                }
            state.sockets += socket
            state.scope
                .launch { handle(state, socket) }
                .invokeOnCompletion {
                    state.sockets -= socket
                    runCatching { socket.close() }
                }
        }
    }

    private suspend fun handle(state: Running, socket: Socket) {
        try {
            socket.soTimeout = readTimeoutMillis
            socket.tcpNoDelay = true
            if (!state.permits.tryAcquire()) {
                respond(socket, HttpResponse(503, "The torrent proxy is busy, try again"), includeBody = true)
                return
            }
            try {
                val request =
                    when (val read = HttpExchange.read(socket.getInputStream(), MAX_HEAD_BYTES)) {
                        is ReadResult.Ok -> read.request
                        ReadResult.Closed -> return
                        ReadResult.Malformed -> return respond(socket, HttpResponse(400, "Malformed request"), includeBody = true)
                        ReadResult.TooLarge -> return respond(socket, HttpResponse(431, "Request head too large"), includeBody = true)
                    }
                respond(socket, route(state, request), includeBody = request.method != "HEAD")
            } finally {
                state.permits.release()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
        } catch (e: IOException) {
        } catch (e: Throwable) {
            errorLog("[MyTorrentClient] Torrent proxy request failed")
            errorLog(e)
            runCatching { respond(socket, HttpResponse(500, "Internal error"), includeBody = true) }
        }
    }

    private suspend fun route(state: Running, request: HttpRequest): HttpResponse {
        if (request.method != "GET" && request.method != "HEAD") {
            return HttpResponse(405, "Only GET and HEAD are supported", listOf("Allow" to "GET, HEAD"))
        }
        if (request.path == PING_PATH) return HttpResponse(200, SIGNATURE)
        if (!request.path.startsWith(ProxyUrl.PATH_PREFIX)) return HttpResponse(404, "Not found")

        val parsed = ProxyUrl.parseQuery(request.rawQuery) ?: return HttpResponse(400, "Missing or invalid torrent link")
        return when (val outcome = state.resolver.resolve(parsed)) {
            is LinkResolver.Outcome.Redirect -> HttpResponse(302, outcome.message, listOf("Location" to outcome.location))
            is LinkResolver.Outcome.NotFound -> HttpResponse(404, outcome.message)
            is LinkResolver.Outcome.Unavailable -> HttpResponse(503, outcome.message)
            is LinkResolver.Outcome.Failed -> HttpResponse(502, outcome.message)
        }
    }

    private fun respond(socket: Socket, response: HttpResponse, includeBody: Boolean) {
        HttpExchange.write(socket.getOutputStream(), response, includeBody)
        runCatching { socket.shutdownOutput() }
    }

    private fun isOurProxy(port: Int): Boolean =
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(LOOPBACK_V4, port), PROBE_TIMEOUT_MILLIS)
                socket.soTimeout = PROBE_TIMEOUT_MILLIS
                socket.getOutputStream().apply {
                    write("GET $PING_PATH HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
                val reply = ByteArrayOutputStream()
                val input = socket.getInputStream()
                val buffer = ByteArray(1024)
                while (reply.size() <= MAX_PROBE_BYTES) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    reply.write(buffer, 0, count)
                }
                val text = reply.toString(Charsets.UTF_8.name())
                text.startsWith("HTTP/1.1 200 ") && text.substringAfter("\r\n\r\n") == SIGNATURE
            }
        } catch (e: IOException) {
            false
        }

    companion object {
        const val PING_PATH = "/torrent-proxy/ping"
        const val SIGNATURE = "Flixclusive Torrent proxy v1"
        private const val BACKLOG = 64
        private const val MAX_HEAD_BYTES = 16 * 1024
        private const val MAX_PROBE_BYTES = 4 * 1024
        private const val PROBE_TIMEOUT_MILLIS = 2_000
        private const val ACCEPT_BACKOFF_MILLIS = 100L
        private val LOOPBACK_V4: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    }
}
