package com.cnife.listenreader.service.ports

import java.io.File

interface TimeProvider {
    fun now(): Long
}
