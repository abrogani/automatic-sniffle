package com.abrogani.servetorr.engine

import com.flixclusive.core.util.log.warnLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.Semaphore

internal class EngineInstallLock(
    private val permits: Semaphore = processPermits(),
    private val pollMillis: Long = POLL_MILLIS,
) {
    suspend fun <T> withLock(lockFile: File, block: suspend () -> T): T {
        while (!permits.tryAcquire()) delay(pollMillis)
        var handle: FileHandle? = null
        try {
            handle = acquireFile(lockFile, wait = true)
            return block()
        } finally {
            release(handle)
        }
    }

    suspend fun <T : Any> tryWithLock(lockFile: File, block: suspend () -> T): T? {
        if (!permits.tryAcquire()) return null
        var handle: FileHandle? = null
        try {
            handle = acquireFile(lockFile, wait = false) ?: return null
            return block()
        } finally {
            release(handle)
        }
    }

    private suspend fun release(handle: FileHandle?) {
        withContext(NonCancellable) { handle?.close() }
        permits.release()
    }

    private suspend fun acquireFile(lockFile: File, wait: Boolean): FileHandle? {
        val channel = try {
            withContext(Dispatchers.IO) {
                lockFile.parentFile?.mkdirs()
                RandomAccessFile(lockFile, "rw").channel
            }
        } catch (e: IOException) {
            warnLog("[ServeTorr] Could not open the engine lock file ${lockFile.absolutePath}: ${e.message}")
            return FileHandle(null, null)
        }
        try {
            while (true) {
                val lock = try {
                    channel.tryLock()
                } catch (e: OverlappingFileLockException) {
                    null
                }
                if (lock != null) return FileHandle(channel, lock)
                if (!wait) {
                    channel.close()
                    return null
                }
                delay(pollMillis)
            }
        } catch (e: Throwable) {
            channel.close()
            if (e is IOException) {
                warnLog("[ServeTorr] Could not lock ${lockFile.absolutePath}: ${e.message}")
                return FileHandle(null, null)
            }
            throw e
        }
    }

    private class FileHandle(private val channel: FileChannel?, private val lock: FileLock?) {
        fun close() {
            runCatching { lock?.release() }
            runCatching { channel?.close() }
        }
    }

    companion object {
        private const val PERMITS_PROPERTY = "abrogani.servetorr.engine.installLock"
        private const val POLL_MILLIS = 100L

        fun processPermits(): Semaphore {
            val properties = System.getProperties()
            return synchronized(properties) {
                properties[PERMITS_PROPERTY] as? Semaphore
                    ?: Semaphore(1).also { properties[PERMITS_PROPERTY] = it }
            }
        }
    }
}
