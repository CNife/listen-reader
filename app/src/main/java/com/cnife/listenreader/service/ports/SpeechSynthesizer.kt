package com.cnife.listenreader.service.ports

import java.io.File

interface SpeechSynthesizer {
    fun isConfigured(): Boolean
    suspend fun synthesize(text: String, model: String, voice: String, destination: File): Long
    suspend fun testConnection(model: String, voice: String): ConnectionTestResult
}
