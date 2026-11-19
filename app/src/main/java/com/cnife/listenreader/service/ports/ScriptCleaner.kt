package com.cnife.listenreader.service.ports

import com.cnife.listenreader.model.SegmentDraft

interface ScriptCleaner {
    fun isConfigured(): Boolean
    suspend fun clean(title: String, markdown: String): CleanResult
    suspend fun testConnection(): ConnectionTestResult
}

sealed interface CleanResult {
    data class Success(val segments: List<SegmentDraft>) : CleanResult
    data class Failure(val message: String, val retryable: Boolean = true) : CleanResult
}

data class ConnectionTestResult(val ok: Boolean, val message: String)
