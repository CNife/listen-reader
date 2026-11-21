package com.cnife.listenreader.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.cnife.listenreader.service.ports.PlaybackEngine
import com.cnife.listenreader.service.ports.PlaybackEngineState
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class Media3PlaybackEngine(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val positionPollMs: Long = 250,
) : PlaybackEngine {

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<PlaybackEngineState>(PlaybackEngineState.Idle)
    override val state: StateFlow<PlaybackEngineState> = _state.asStateFlow()

    private val connectMutex = Mutex()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pollJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.value = PlaybackEngineState.Error("无法播放音频：${error.errorCodeName}")
        }
    }

    override suspend fun prepare(file: File, startPositionMs: Long) {
        _state.value = PlaybackEngineState.Preparing
        val player = controller()
        withContext(Dispatchers.Main.immediate) {
            player.setMediaItem(
                MediaItem.Builder()
                    .setUri(Uri.fromFile(file))
                    .setMimeType(MimeTypes.AUDIO_MPEG)
                    .build(),
            )
            player.prepare()
            if (startPositionMs > 0) player.seekTo(startPositionMs)
        }
    }

    override fun play() {
        launchController { it.play() }
    }

    override fun pause() {
        launchController { it.pause() }
    }

    override fun setSpeed(speed: Float) {
        launchController { it.setPlaybackSpeed(speed.coerceIn(0.5f, 2.0f)) }
    }

    override fun release() {
        pollJob?.cancel()
        pollJob = null
        val future = controllerFuture
        val player = controller
        controller = null
        controllerFuture = null
        runCatching {
            player?.removeListener(listener)
            future?.let { MediaController.releaseFuture(it) }
        }
        _state.value = PlaybackEngineState.Idle
    }

    private fun launchController(block: (MediaController) -> Unit) {
        scope.launch {
            val player = runCatching { controller() }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main.immediate) { block(player) }
        }
    }

    private suspend fun controller(): MediaController {
        controller?.let { return it }
        return connectMutex.withLock {
            controller?.let { return@withLock it }
            val future = controllerFuture ?: MediaController.Builder(
                appContext,
                SessionToken(
                    appContext,
                    ComponentName(appContext, PlaybackService::class.java),
                ),
            ).buildAsync().also { controllerFuture = it }
            val player = withContext(Dispatchers.Main.immediate) { future.await() }
            player.addListener(listener)
            controller = player
            startPolling()
            player
        }
    }

    private fun startPolling() {
        if (pollJob != null) return
        pollJob = scope.launch {
            while (isActive) {
                controller?.let { publish(it) }
                delay(positionPollMs)
            }
        }
    }

    private fun publish(player: Player) {
        when (player.playbackState) {
            Player.STATE_READY -> {
                _state.value = PlaybackEngineState.Ready(
                    durationMs = player.duration.coerceAtLeast(0),
                    isPlaying = player.isPlaying,
                    positionMs = player.currentPosition.coerceAtLeast(0),
                )
            }
            Player.STATE_ENDED -> {
                _state.value = PlaybackEngineState.Ended(player.duration.coerceAtLeast(0))
            }
            else -> Unit
        }
    }
}
