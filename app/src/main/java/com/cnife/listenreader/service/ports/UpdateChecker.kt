package com.cnife.listenreader.service.ports

data class RemoteRelease(
    val version: String,
    val url: String,
    val notes: String,
)

interface UpdateChecker {
    suspend fun latestRelease(): RemoteRelease
}

class UpdateCheckException(message: String) : Exception(message)
