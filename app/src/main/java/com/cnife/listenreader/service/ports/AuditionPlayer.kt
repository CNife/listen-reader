package com.cnife.listenreader.service.ports

import java.io.File

interface AuditionPlayer {
    fun play(file: File)
}

object NoopAuditionPlayer : AuditionPlayer {
    override fun play(file: File) = Unit
}
