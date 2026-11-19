package com.cnife.listenreader.service.ports

import java.io.File

/**
 * 音色试听用的独立播放器：绝不能打断主播放器。
 * 生产实现用单例 MediaPlayer；测试注入 fake。
 */
interface AuditionPlayer {
    fun play(file: File)
    fun stop()
}

object NoopAuditionPlayer : AuditionPlayer {
    override fun play(file: File) = Unit
    override fun stop() = Unit
}
