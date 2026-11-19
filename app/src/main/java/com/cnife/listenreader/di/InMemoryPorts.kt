package com.cnife.listenreader.di

import com.cnife.listenreader.service.ports.TimeProvider

class SystemTimeProvider : TimeProvider {
    override fun now(): Long = System.currentTimeMillis()
}
