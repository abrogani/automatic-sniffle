package com.abrogani.servetorr.engine

import java.io.File

internal object EngineUpdate {
    const val WANTED_KEY = "engine_wanted"

    fun previousVersions(engineRoot: File, current: String): List<String> =
        engineRoot.listFiles()
            ?.filter { it.isDirectory && it.name != current && File(it, EngineSource.LIBRARY_NAME).isFile }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    fun shouldUpdate(
        installed: Boolean,
        wanted: Boolean,
        previousVersions: List<String>,
    ): Boolean = !installed && (wanted || previousVersions.isNotEmpty())
}
