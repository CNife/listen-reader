package com.cnife.listenreader.service.ports

import java.io.File
import kotlinx.coroutines.flow.StateFlow

interface PlaybackEngine {
    val state: StateFlow<PlaybackEngineState>
    suspend fun prepare(file: File, startPositionMs: Long = 0)
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun setSpeed(speed: Float)
    fun release()
}

sealed interface PlaybackEngineState {
    data object Idle : PlaybackEngineState
    data object Preparing : PlaybackEngineState
    data class Ready(val durationMs: Long, val isPlaying: Boolean, val positionMs: Long) : PlaybackEngineState
    data class Ended(val durationMs: Long) : PlaybackEngineState
    data class Error(val message: String) : PlaybackEngineState
}
