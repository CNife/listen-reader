package com.cnife.listenreader.service.ports

import android.content.Context
import android.view.View
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface PageSession {
    val state: StateFlow<PageSessionState>

    val expandProgress: StateFlow<String?> get() = NoExpandProgress

    fun createView(context: Context): View
    fun load(url: String)
    fun reload()
    fun goBack(): Boolean

    suspend fun expandAndExtract(): PageExtraction
    fun release()
}

sealed interface PageSessionState {
    data object Idle : PageSessionState
    data class Loading(val progress: Int = 0) : PageSessionState
    data object Ready : PageSessionState
    data class NeedsUserAction(val reason: String) : PageSessionState
    data class Failed(val message: String) : PageSessionState
}

data class PageExtraction(
    val title: String,
    val byline: String?,
    val publishedAt: String?,
    val markdown: String,
    val text: String,
    val pageText: String,
    val extractor: String,
    val score: Double,
    val confidence: Double,
    val needsExpand: Boolean,
    val degradedReason: String? = null,
    val html: String? = null,
    val expandRounds: Int = 0,
)

private val NoExpandProgress: StateFlow<String?> = MutableStateFlow<String?>(null)

class PageExtractionException(message: String) : Exception(message)
