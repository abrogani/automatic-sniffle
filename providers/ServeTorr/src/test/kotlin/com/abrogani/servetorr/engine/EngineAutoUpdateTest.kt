package com.abrogani.servetorr.engine

import com.flixclusive.core.util.log.LogRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isNotNull
import strikt.assertions.isNull
import strikt.assertions.isTrue
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

class EngineAutoUpdateTest {
    @get:Rule
    val logRule = LogRule()

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `does nothing when no update is needed`(): Unit = runBlocking {
        val installs = AtomicInteger()
        EngineAutoUpdate(
            updateNeeded = { false },
            install = { installs.incrementAndGet() },
            blockReason = { null },
            wakeups = emptyFlow(),
            pending = MutableStateFlow(false),
        ).run()
        expectThat(installs.get()).isEqualTo(0)
    }

    @Test
    fun `waits while blocked and installs when the network allows it`(): Unit = runBlocking {
        val installs = AtomicInteger()
        val blocked = MutableStateFlow(true)
        val wakeups = MutableSharedFlow<Any?>(extraBufferCapacity = 1)
        val pending = MutableStateFlow(false)
        val job = launch(Dispatchers.Default) {
            EngineAutoUpdate(
                updateNeeded = { true },
                install = { installs.incrementAndGet() },
                blockReason = { if (blocked.value) "off Wi-Fi" else null },
                wakeups = wakeups,
                pending = pending,
            ).run()
        }
        withTimeout(5_000) { while (!pending.value) delay(10) }
        expectThat(installs.get()).isEqualTo(0)

        blocked.value = false
        withTimeout(5_000) {
            while (installs.get() == 0) {
                wakeups.tryEmit(Unit)
                delay(10)
            }
        }
        job.join()
        expectThat(installs.get()).isEqualTo(1)
        expectThat(pending.value).isFalse()
    }

    @Test
    fun `stops waiting when the update is no longer wanted`(): Unit = runBlocking {
        val installs = AtomicInteger()
        val needed = MutableStateFlow(true)
        val wakeups = MutableSharedFlow<Any?>(extraBufferCapacity = 1)
        val pending = MutableStateFlow(false)
        val job = launch(Dispatchers.Default) {
            EngineAutoUpdate(
                updateNeeded = { needed.value },
                install = { installs.incrementAndGet() },
                blockReason = { "off Wi-Fi" },
                wakeups = wakeups,
                pending = pending,
            ).run()
        }
        withTimeout(5_000) { while (!pending.value) delay(10) }
        needed.value = false
        withTimeout(5_000) {
            while (job.isActive) {
                wakeups.tryEmit(Unit)
                delay(10)
            }
        }
        expectThat(installs.get()).isEqualTo(0)
        expectThat(pending.value).isFalse()
    }

    @Test
    fun `falls back to polling without a wakeup`(): Unit = runBlocking {
        val installs = AtomicInteger()
        val checks = AtomicInteger()
        EngineAutoUpdate(
            updateNeeded = { true },
            install = { installs.incrementAndGet() },
            blockReason = { if (checks.incrementAndGet() < 3) "off Wi-Fi" else null },
            wakeups = emptyFlow(),
            pending = MutableStateFlow(false),
            fallbackMillis = 10,
        ).run()
        expectThat(installs.get()).isEqualTo(1)
    }

    @Test
    fun `failures are logged instead of thrown`(): Unit = runBlocking {
        EngineAutoUpdate(
            updateNeeded = { throw IllegalStateException("DataStore failed") },
            install = {},
            blockReason = { null },
            wakeups = emptyFlow(),
            pending = MutableStateFlow(false),
        ).run()

        val checks = AtomicInteger()
        val installs = AtomicInteger()
        EngineAutoUpdate(
            updateNeeded = { true },
            install = { installs.incrementAndGet() },
            blockReason = { if (checks.incrementAndGet() == 1) throw IllegalStateException("boom") else null },
            wakeups = emptyFlow(),
            pending = MutableStateFlow(false),
            fallbackMillis = 10,
        ).run()
        expectThat(installs.get()).isEqualTo(1)
    }

    @Test
    fun `registration check uses identity and tolerates a missing registry`(): Unit = runBlocking {
        val self = Any()
        val twin = Any()
        expectThat(PluginRegistration.isRegistered(self) { null }).isTrue()
        expectThat(PluginRegistration.isRegistered(self) { listOf(twin, self) }).isTrue()
        expectThat(PluginRegistration.isRegistered(self, waitMillis = 0) { listOf(twin) }).isFalse()

        var calls = 0
        expectThat(
            PluginRegistration.isRegistered(self, waitMillis = 1_000, stepMillis = 5) {
                if (++calls < 3) listOf(twin) else listOf(self)
            }
        ).isTrue()
    }

    @Test
    fun `install lock serialises holders across instances sharing permits`(): Unit = runBlocking {
        val permits = Semaphore(1)
        val lockFile = File(temp.root, "torrent/${EngineUpdate.LOCK_FILE}")
        val first = EngineInstallLock(permits, pollMillis = 5)
        val second = EngineInstallLock(permits, pollMillis = 5)
        val inside = AtomicInteger()
        val maxInside = AtomicInteger()

        val jobs = (0 until 4).map { i ->
            async(Dispatchers.Default) {
                (if (i % 2 == 0) first else second).withLock(lockFile) {
                    maxInside.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
                    delay(20)
                    inside.decrementAndGet()
                }
            }
        }
        jobs.forEach { it.await() }
        expectThat(maxInside.get()).isEqualTo(1)
        expectThat(lockFile.isFile).isTrue()

        val held = launch(Dispatchers.Default) { first.withLock(lockFile) { delay(200) } }
        withTimeout(5_000) { while (permits.availablePermits() > 0) yield() }
        expectThat(second.tryWithLock(lockFile) { "ran" }).isNull()
        held.join()
        expectThat(second.tryWithLock(lockFile) { "ran" }).isNotNull()
    }

    @Test
    fun `missing engine assets are reported as not published`() {
        val missing = EngineInstaller.rangeFailure(404)
        expectThat(missing is EngineInstaller.EngineNotPublished).isTrue()
        expectThat(missing?.message).isEqualTo("Engine v${EngineSource.VERSION} isn't published yet.")
        expectThat(EngineInstaller.rangeFailure(206)).isNull()
        expectThat(EngineInstaller.rangeFailure(200) is EngineInstaller.RangeNotSupported).isTrue()

        expectThat(EngineInstaller.downloadFailure(200)).isNull()
        expectThat(EngineInstaller.downloadFailure(206)).isNull()
        expectThat(EngineInstaller.downloadFailure(404) is EngineInstaller.EngineNotPublished).isTrue()
        expectThat(EngineInstaller.downloadFailure(500)?.message).isEqualTo("Engine download failed [500]")
    }
}
