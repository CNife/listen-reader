package com.cnife.listenreader.service.ports

import android.content.Context
import android.view.View
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface PageSession {
    val state: StateFlow<PageSessionState>

    /** L3 规则展开期间的可读进度（如「正在展开全文…（第 1 轮）」）；不在展开时为空。 */
    val expandProgress: StateFlow<String?> get() = NoExpandProgress

    fun createView(context: Context): View
    fun load(url: String)
    fun reload()
    fun goBack(): Boolean

    /** 先跑 L1；needsExpand 时按规则点击展开类元素（最多 2 轮）并重新提取。 */
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
    /** 提取后正文 HTML，用于离线快照（可选）。 */
    val html: String? = null,
    /** L3 规则展开实际点击的轮数（0 表示没有展开动作）。 */
    val expandRounds: Int = 0,
)

/** 不支持 L3 进度的 PageSession 共用的空进度。 */
private val NoExpandProgress: StateFlow<String?> = MutableStateFlow<String?>(null)

/** 页面无法提取时的可读原因；由 ReadingService 映射为页面错误。 */
class PageExtractionException(message: String) : Exception(message)
