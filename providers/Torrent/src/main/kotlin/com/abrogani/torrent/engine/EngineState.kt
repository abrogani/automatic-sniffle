package com.abrogani.torrent.engine

internal sealed interface EngineState {
    data object NotInstalled : EngineState

    data class Installing(val downloaded: Long, val total: Long) : EngineState

    /** Installed, not running in this process. */
    data object Stopped : EngineState

    data class Running(val port: Int) : EngineState

    data class Failed(val message: String) : EngineState
}
