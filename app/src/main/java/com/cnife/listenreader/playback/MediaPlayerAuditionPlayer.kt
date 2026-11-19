package com.cnife.listenreader.playback

import android.media.MediaPlayer
import android.util.Log
import com.cnife.listenreader.service.ports.AuditionPlayer
import java.io.File

/** 用独立 MediaPlayer 播放试听音频，与主播放器的 Media3 会话互不影响。 */
class MediaPlayerAuditionPlayer : AuditionPlayer {

    private var player: MediaPlayer? = null

    override fun play(file: File) {
        stop()
        if (!file.isFile) return
        val created = runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stop() }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "audition error what=$what extra=$extra")
                    stop()
                    true
                }
                prepare()
                start()
            }
        }
        created.onSuccess { player = it }
        created.onFailure { Log.w(TAG, "audition failed: ${it.message}") }
    }

    override fun stop() {
        val current = player ?: return
        player = null
        runCatching {
            if (current.isPlaying) current.stop()
        }
        runCatching { current.release() }
    }

    private companion object {
        const val TAG = "AuditionPlayer"
    }
}
