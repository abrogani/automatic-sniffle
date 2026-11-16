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
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

internal class EngineInstaller(client: OkHttpClient) {
    private val downloadClient = client.newBuilder()
        .callTimeout(3, TimeUnit.MINUTES)
        .build()

    suspend fun install(abi: String, engineDir: File, onProgress: (Long, Long) -> Unit) = withIOContext {
        val files = EngineSource.engineFiles(abi) ?: throw IllegalArgumentException("Unsupported ABI $abi")
        val attempt = UUID.randomUUID().toString()
        val temps = files.associate { file ->
            file.entryName to ExtractTarget(
                file = File(engineDir, "${file.fileName}.$attempt.tmp").also { it.setWritable(true); it.delete() },
                sha256 = file.sha256,
                maxBytes = file.maxBytes,
            )
        }

        try {
            val ranges = try {
                locateEntries(files.map { it.entryName })
            } catch (e: RangeNotSupported) {
                warnLog("[ServeTorr] Server ignored Range; downloading the whole engine archive")
                null
            }

            if (ranges == null) {
                download(null, temps, 0L, null, onProgress)
            } else {
                val total = ranges.values.sumOf { it.length }
                var done = 0L
                for (file in files) {
                    val range = ranges.getValue(file.entryName)
                    download(range, mapOf(file.entryName to temps.getValue(file.entryName)), done, total, onProgress)
                    done += range.length
                }
            }

            for (file in files) {
                val temp = temps.getValue(file.entryName).file
                val target = File(engineDir, file.fileName)
                target.setWritable(true)
                target.delete()
                check(temp.renameTo(target)) { "Could not store the torrent engine" }
                target.setReadOnly()
            }
            infoLog("[ServeTorr] Installed engine ${EngineSource.VERSION} ($abi)")
        } catch (e: Throwable) {
            temps.values.forEach { it.file.delete() }
            throw e
        }
    }

    private suspend fun download(
        range: ZipDirectory.EntryRange?,
        targets: Map<String, ExtractTarget>,
        offset: Long,
        knownTotal: Long?,
        onProgress: (Long, Long) -> Unit,
    ) {
        val request = Request.Builder().url(EngineSource.AAR_URL).apply {
            if (range != null) header("Range", "bytes=${range.start}-${range.endExclusive - 1}")
        }.build()

        downloadClient.newCall(request).execute().use { response ->
            downloadFailure(response.code)?.let { throw it }

            val total = when {
                knownTotal != null && response.code == 206 -> knownTotal
                else -> offset + response.body.contentLength()
            }
            if (total > EngineSource.MAX_ARCHIVE_BYTES) throw SecurityException("Engine archive is larger than expected")

            val job = currentCoroutineContext()[Job]
            val counting = CountingInputStream(response.body.byteStream(), EngineSource.MAX_ARCHIVE_BYTES) {
                job?.ensureActive()
                onProgress(offset + it, total)
            }
            extractEntries(counting, targets)
        }
    }

    private suspend fun locateEntries(names: List<String>): Map<String, ZipDirectory.EntryRange> {
        val tailRequest = Request.Builder()
            .url(EngineSource.AAR_URL)
            .header("Range", "bytes=-${ZipDirectory.TAIL_SIZE}")
            .build()

        val (tail, archiveSize) = downloadClient.newCall(tailRequest).execute().use { response ->
            rangeFailure(response.code)?.let { throw it }
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
        return names.associateWith { name ->
            ZipDirectory.locate(entries, end, name) ?: throw IOException("$name is missing from the engine archive")
        }
    }

    private fun fetchRange(start: Long, endExclusive: Long): ByteArray {
        if (endExclusive - start > EngineSource.MAX_ARCHIVE_BYTES) throw SecurityException("Engine archive is larger than expected")

        val request = Request.Builder()
            .url(EngineSource.AAR_URL)
            .header("Range", "bytes=$start-${endExclusive - 1}")
            .build()

        return downloadClient.newCall(request).execute().use { response ->
            rangeFailure(response.code)?.let { throw it }
            response.body.bytes()
        }
    }

    internal class RangeNotSupported : IOException()

    internal class EngineNotPublished(version: String) : IOException("Engine v$version isn't published yet.")

    internal data class ExtractTarget(val file: File, val sha256: String, val maxBytes: Long)

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

        private const val NOT_FOUND = 404

        fun rangeFailure(code: Int): IOException? =
            when (code) {
                206 -> null
                NOT_FOUND -> EngineNotPublished(EngineSource.VERSION)
                else -> RangeNotSupported()
            }

        fun downloadFailure(code: Int): IOException? =
            when {
                code in 200..299 -> null
                code == NOT_FOUND -> EngineNotPublished(EngineSource.VERSION)
                else -> IOException("Engine download failed [$code]")
            }

        private val CONTENT_RANGE_TOTAL = Regex("""bytes \d+-\d+/(\d+)""")

        private fun Response.archiveSize(): Long? =
            header("Content-Range")?.let { CONTENT_RANGE_TOTAL.find(it) }?.groupValues?.get(1)?.toLongOrNull()

        fun extractEntry(input: InputStream, entryName: String, target: File, expectedSha256: String) =
            extractEntries(input, mapOf(entryName to ExtractTarget(target, expectedSha256, EngineSource.MAX_LIBRARY_BYTES)))

        fun extractEntries(input: InputStream, targets: Map<String, ExtractTarget>) {
            val remaining = targets.toMutableMap()
            ZipInputStream(input).use { zip ->
                while (remaining.isNotEmpty()) {
                    val entry = zip.nextEntry
                        ?: throw IOException("${remaining.keys.first()} is missing from the engine archive")
                    val target = remaining.remove(entry.name) ?: continue
                    writeVerified(zip, entry.name, target)
                }
            }
        }

        private fun writeVerified(input: InputStream, entryName: String, target: ExtractTarget) {
            val digest = MessageDigest.getInstance("SHA-256")
            target.file.outputStream().use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break

                    total += read
                    if (total > target.maxBytes) throw SecurityException("$entryName is larger than expected")
                    digest.update(buffer, 0, read)
                    out.write(buffer, 0, read)
                }
            }

            val actual = digest.digest().toHex()
            if (actual != target.sha256) {
                throw SecurityException("Engine checksum mismatch for $entryName (expected ${target.sha256}, got $actual)")
            }
        }

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
