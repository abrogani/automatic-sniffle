package com.abrogani.servetorr.engine

internal sealed interface EngineState {
    data object NotInstalled : EngineState

    data class Installing(val downloaded: Long, val total: Long) : EngineState

    data object Stopped : EngineState

    data class Running(val port: Int) : EngineState

    data class Failed(val message: String) : EngineState

    data class RestartRequired(val reason: String) : EngineState
}
