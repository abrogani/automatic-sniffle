package com.abrogani.servetorr.engine

import java.io.File
import java.math.BigInteger
import java.util.UUID

internal object EngineUpdate {
    const val WANTED_KEY = "engine_wanted"

    private val NUMERIC = Regex("""\d+""")

    enum class Pins { MATCH, MISMATCH, UNKNOWN }

    fun versionNumber(name: String): BigInteger? =
        name.takeIf { NUMERIC.matches(it) }?.toBigInteger()

    fun isNewerVersion(name: String, current: String): Boolean {
        val candidate = versionNumber(name) ?: return false
        val ours = versionNumber(current) ?: return false
        return candidate > ours
    }

    fun isNewerInstall(dir: File, current: String): Boolean =
        dir.isDirectory &&
            isNewerVersion(dir.name, current) &&
            (File(dir, EngineSource.DEX_NAME).isFile || File(dir, PINS_MARKER).isFile)

    fun previousVersions(engineRoot: File, current: String): List<String> =
        engineRoot.listFiles()
            ?.filter {
                it.isDirectory && it.name != current && File(it, EngineSource.LIBRARY_NAME).isFile && !isNewerInstall(it, current)
            }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    fun newerVersions(engineRoot: File, current: String): List<String> =
        engineRoot.listFiles()
            ?.filter { isNewerInstall(it, current) && File(it, EngineSource.LIBRARY_NAME).isFile }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    fun replacedByInstall(engineRoot: File, current: String): List<File> =
        engineRoot.listFiles()
            ?.filter { it.name != current && !isNewerInstall(it, current) }
            .orEmpty()

    fun shouldUpdate(
        installed: Boolean,
        wanted: Boolean,
        previousVersions: List<String>,
        stalePins: Boolean = false,
        newerInstalled: Boolean = false,
    ): Boolean = !installed && !newerInstalled && (wanted || previousVersions.isNotEmpty() || stalePins)

    fun readWanted(marker: File): Boolean? =
        when (runCatching { marker.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()) {
            WANTED -> true
            NOT_WANTED -> false
            else -> null
        }

    fun writeWanted(marker: File, wanted: Boolean) = writeAtomically(marker, if (wanted) WANTED else NOT_WANTED)

    fun pinsState(marker: File, expected: String?): Pins {
        val stored = runCatching { marker.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
        return when {
            stored.isNullOrEmpty() -> Pins.UNKNOWN
            expected != null && stored == expected -> Pins.MATCH
            else -> Pins.MISMATCH
        }
    }

    fun writePins(marker: File, fingerprint: String) = writeAtomically(marker, fingerprint)

    private fun writeAtomically(target: File, text: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            temp.writeText(text)
            if (!temp.renameTo(target)) {
                target.delete()
                check(temp.renameTo(target)) { "Could not write ${target.absolutePath}" }
            }
        } finally {
            temp.delete()
        }
    }

    const val PINS_MARKER = ".pins"
    const val WANTED_MARKER = "engine_wanted"
    const val LOCK_FILE = ".engine.lock"

    private const val WANTED = "1"
    private const val NOT_WANTED = "0"
}
