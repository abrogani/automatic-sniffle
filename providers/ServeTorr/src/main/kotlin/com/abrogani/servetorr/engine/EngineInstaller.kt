package com.abrogani.servetorr.engine

import com.flixclusive.core.util.coroutines.FlxDispatchers.Companion.withIOContext
import com.flixclusive.core.util.log.infoLog
import com.flixclusive.core.util.log.warnLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

internal class EngineInstaller(client: OkHttpClient) {
    private val downloadClient = client.newBuilder()
        .callTimeout(3, TimeUnit.MINUTES)
        .build()

    suspend fun install(abi: String, target: File, onProgress: (Long, Long) -> Unit) = withIOContext {
        val expected = EngineSource.LIBRARY_SHA256[abi]
            ?: throw IllegalArgumentException("Unsupported ABI $abi")
        val entryName = EngineSource.entryName(abi)

        val range = try {
            locateEntry(entryName)
        } catch (e: RangeNotSupported) {
            warnLog("[ServeTorr] Server ignored Range; downloading the whole engine archive")
            null
        }

        val request = Request.Builder().url(EngineSource.AAR_URL).apply {
            if (range != null) header("Range", "bytes=${range.start}-${range.endExclusive - 1}")
        }.build()

        val temp = File(target.parentFile, "${target.name}.tmp").also { it.setWritable(true); it.delete() }
        try {
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Engine download failed [${response.code}]")

                val total = when {
                    range != null && response.code == 206 -> range.length
                    else -> response.body.contentLength()
                }
                if (total > EngineSource.MAX_ARCHIVE_BYTES) throw SecurityException("Engine archive is larger than expected")

                val job = coroutineContext[Job]
                val counting = CountingInputStream(response.body.byteStream(), EngineSource.MAX_ARCHIVE_BYTES) {
                    job?.ensureActive()
                    onProgress(it, total)
                }
                extractEntry(counting, entryName, temp, expected)
            }

            target.setWritable(true)
            target.delete()
            check(temp.renameTo(target)) { "Could not store the torrent engine" }
            target.setReadOnly()
            infoLog("[ServeTorr] Installed engine ${EngineSource.VERSION} ($abi)")
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }

    private suspend fun locateEntry(name: String): ZipDirectory.EntryRange {
        val tailRequest = Request.Builder()
            .url(EngineSource.AAR_URL)
            .header("Range", "bytes=-${ZipDirectory.TAIL_SIZE}")
            .build()

        val (tail, archiveSize) = downloadClient.newCall(tailRequest).execute().use { response ->
            if (response.code != 206) throw RangeNotSupported()
            val size = response.archiveSize() ?: throw RangeNotSupported()
            response.body.bytes() to size
        }
        currentCoroutineContext().ensureActive()

        val end = ZipDirectory.readEndRecord(tail)
        val tailStart = archiveSize - tail.size
        val central = if (end.centralOffset >= tailStart) {
            val from = (end.centralOffset - tailStart).toInt()
            tail.copyOfRange(from, (from + end.centralSize).toInt().coerceAtMost(tail.size))
        } else {
            fetchRange(end.centralOffset, end.centralOffset + end.centralSize)
        }

        val entries = ZipDirectory.readCentralDirectory(central, end.entryCount)
        return ZipDirectory.locate(entries, end, name)
            ?: throw IOException("$name is missing from the engine archive")
    }

    private fun fetchRange(start: Long, endExclusive: Long): ByteArray {
        if (endExclusive - start > EngineSource.MAX_ARCHIVE_BYTES) throw SecurityException("Engine archive is larger than expected")

        val request = Request.Builder()
            .url(EngineSource.AAR_URL)
            .header("Range", "bytes=$start-${endExclusive - 1}")
            .build()

        return downloadClient.newCall(request).execute().use { response ->
            if (response.code != 206) throw RangeNotSupported()
            response.body.bytes()
        }
    }

    private class RangeNotSupported : IOException()

    internal class CountingInputStream(
        input: InputStream,
        private val limit: Long,
        private val onProgress: (Long) -> Unit,
    ) : FilterInputStream(input) {
        private var count = 0L
        private var reported = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) advance(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = super.read(b, off, len)
            if (read > 0) advance(read.toLong())
            return read
        }

        private fun advance(by: Long) {
            count += by
            if (count > limit) throw SecurityException("Engine archive is larger than expected")
            if (count - reported >= PROGRESS_STEP) {
                reported = count
                onProgress(count)
            }
        }
    }

    internal companion object {
        private const val PROGRESS_STEP = 64L * 1024

        private val CONTENT_RANGE_TOTAL = Regex("""bytes \d+-\d+/(\d+)""")

        private fun Response.archiveSize(): Long? =
            header("Content-Range")?.let { CONTENT_RANGE_TOTAL.find(it) }?.groupValues?.get(1)?.toLongOrNull()

        fun extractEntry(input: InputStream, entryName: String, target: File, expectedSha256: String) {
            val digest = MessageDigest.getInstance("SHA-256")
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: throw IOException("$entryName is missing from the engine archive")
                    if (entry.name != entryName) continue

                    target.outputStream().use { out ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break

                            total += read
                            if (total > EngineSource.MAX_LIBRARY_BYTES) throw SecurityException("Engine library is larger than expected")
                            digest.update(buffer, 0, read)
                            out.write(buffer, 0, read)
                        }
                    }
                    break
                }
            }

            val actual = digest.digest().toHex()
            if (actual != expectedSha256) {
                throw SecurityException("Engine checksum mismatch (expected $expectedSha256, got $actual)")
            }
        }

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
