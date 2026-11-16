package com.abrogani.servetorr.engine

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import strikt.api.expectThat
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isNotEqualTo
import strikt.assertions.isNull
import strikt.assertions.isTrue
import java.io.File

class EngineUpdateTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `updates when a previous version was installed or the user wanted the engine`() {
        expectThat(EngineUpdate.shouldUpdate(installed = false, wanted = false, previousVersions = listOf("1"))).isTrue()
        expectThat(EngineUpdate.shouldUpdate(installed = false, wanted = true, previousVersions = emptyList())).isTrue()
    }

    @Test
    fun `does nothing when installed or never wanted`() {
        expectThat(EngineUpdate.shouldUpdate(installed = true, wanted = true, previousVersions = listOf("1"))).isFalse()
        expectThat(EngineUpdate.shouldUpdate(installed = false, wanted = false, previousVersions = emptyList())).isFalse()
    }

    @Test
    fun `stale pins need an update and a newer install blocks it`() {
        expectThat(
            EngineUpdate.shouldUpdate(installed = false, wanted = false, previousVersions = emptyList(), stalePins = true)
        ).isTrue()
        expectThat(
            EngineUpdate.shouldUpdate(installed = false, wanted = true, previousVersions = listOf("1"), newerInstalled = true)
        ).isFalse()
    }

    @Test
    fun `previous versions are other dirs holding the engine library`() {
        val root = temp.newFolder("engine")
        root.version("1")
        root.version("st-1")
        root.version("2")
        File(root, "empty").mkdirs()
        File(root, "stray.bin").writeText("x")

        expectThat(EngineUpdate.previousVersions(root, current = "2")).isEqualTo(listOf("1", "st-1"))
        expectThat(EngineUpdate.previousVersions(File(temp.root, "missing"), current = "2")).isEqualTo(emptyList())
    }

    @Test
    fun `versions compare numerically`() {
        expectThat(EngineUpdate.isNewerVersion("10", "9")).isTrue()
        expectThat(EngineUpdate.isNewerVersion("2", "1")).isTrue()
        expectThat(EngineUpdate.isNewerVersion("1", "1")).isFalse()
        expectThat(EngineUpdate.isNewerVersion("1", "2")).isFalse()
        expectThat(EngineUpdate.isNewerVersion("st-1", "1")).isFalse()
        expectThat(EngineUpdate.isNewerVersion("99999999999999999999999", "1")).isTrue()
    }

    @Test
    fun `an install replaces older and legacy dirs but keeps newer ones`() {
        val root = temp.newFolder("engine")
        root.version("1", dex = true)
        root.version("2", dex = true)
        root.version("st-1")
        root.version("7861970")
        root.version("3", dex = true)
        root.version("10").also { File(it, EngineUpdate.PINS_MARKER).writeText("x") }
        File(root, "stray.bin").writeText("x")

        expectThat(EngineUpdate.replacedByInstall(root, current = "2").map { it.name })
            .containsExactlyInAnyOrder("1", "st-1", "7861970", "stray.bin")
        expectThat(EngineUpdate.previousVersions(root, current = "2")).isEqualTo(listOf("1", "7861970", "st-1"))
        expectThat(EngineUpdate.newerVersions(root, current = "2")).isEqualTo(listOf("10", "3"))
    }

    @Test
    fun `wanted marker`() {
        val marker = File(temp.root, "torrent/${EngineUpdate.WANTED_MARKER}")
        expectThat(EngineUpdate.readWanted(marker)).isNull()

        EngineUpdate.writeWanted(marker, true)
        expectThat(EngineUpdate.readWanted(marker)).isEqualTo(true)

        EngineUpdate.writeWanted(marker, false)
        expectThat(EngineUpdate.readWanted(marker)).isEqualTo(false)

        marker.writeText("garbage")
        expectThat(EngineUpdate.readWanted(marker)).isNull()
        expectThat(marker.parentFile.listFiles()!!.map { it.name }).isEqualTo(listOf(EngineUpdate.WANTED_MARKER))
    }

    @Test
    fun `pins marker`() {
        val marker = File(temp.root, "1/${EngineUpdate.PINS_MARKER}")
        expectThat(EngineUpdate.pinsState(marker, "abc")).isEqualTo(EngineUpdate.Pins.UNKNOWN)

        EngineUpdate.writePins(marker, "abc")
        expectThat(EngineUpdate.pinsState(marker, "abc")).isEqualTo(EngineUpdate.Pins.MATCH)
        expectThat(EngineUpdate.pinsState(marker, "def")).isEqualTo(EngineUpdate.Pins.MISMATCH)
        expectThat(EngineUpdate.pinsState(marker, null)).isEqualTo(EngineUpdate.Pins.MISMATCH)
    }

    @Test
    fun `fingerprint covers the version and every pinned hash`() {
        val base = EngineSource.fingerprint("1", listOf("a.so" to "AA", "b.dex" to "bb"))
        expectThat(EngineSource.fingerprint("1", listOf("b.dex" to "bb", "a.so" to "aa"))).isEqualTo(base)
        expectThat(EngineSource.fingerprint("2", listOf("a.so" to "aa", "b.dex" to "bb"))).isNotEqualTo(base)
        expectThat(EngineSource.fingerprint("1", listOf("a.so" to "ab", "b.dex" to "bb"))).isNotEqualTo(base)

        val abi = EngineSource.LIBRARY_SHA256.keys.first()
        val pinned = EngineSource.engineFiles(abi)!!.map { it.fileName to it.sha256 }
        expectThat(EngineSource.pinFingerprint(abi)).isEqualTo(EngineSource.fingerprint(EngineSource.VERSION, pinned))
        expectThat(EngineSource.pinFingerprint("mips")).isNull()
    }

    private fun File.version(name: String, dex: Boolean = false): File =
        File(this, name).apply {
            mkdirs()
            File(this, EngineSource.LIBRARY_NAME).writeText("x")
            if (dex) File(this, EngineSource.DEX_NAME).writeText("x")
        }
}
