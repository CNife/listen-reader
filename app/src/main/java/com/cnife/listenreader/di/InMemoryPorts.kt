package com.cnife.listenreader.di

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.cnife.listenreader.service.ports.CleanResult
import com.cnife.listenreader.service.ports.ConnectionTestResult
import com.cnife.listenreader.service.ports.PageExtraction
import com.cnife.listenreader.service.ports.PageSession
import com.cnife.listenreader.service.ports.PageSessionState
import com.cnife.listenreader.service.ports.ScriptCleaner
import com.cnife.listenreader.service.ports.TimeProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SystemTimeProvider : TimeProvider {
    override fun now(): Long = System.currentTimeMillis()
}

/** T6 之前的空页面会话。 */
class UnavailablePageSession : PageSession {
    private val pageState = MutableStateFlow<PageSessionState>(PageSessionState.Idle)
    override val state: StateFlow<PageSessionState> = pageState.asStateFlow()

    override fun createView(context: Context): View = FrameLayout(context)

    override fun load(url: String) {
        pageState.value = PageSessionState.Failed("页面采集将在后续版本接入")
    }

    override fun reload() = Unit

    override fun goBack(): Boolean = false

    override suspend fun extract(): PageExtraction = emptyExtraction()

    override suspend fun expandAndExtract(): PageExtraction = extract()

    override fun release() = Unit

    private fun emptyExtraction() = PageExtraction(
        title = "",
        byline = null,
        publishedAt = null,
        markdown = "",
        text = "",
        pageText = "",
        extractor = "unavailable",
        score = 0.0,
        confidence = 0.0,
        needsExpand = false,
    )
}

/** T4 之前的空 L2 清洗器。 */
class UnconfiguredScriptCleaner : ScriptCleaner {
    override fun isConfigured(): Boolean = false

    override suspend fun clean(title: String, markdown: String): CleanResult =
        CleanResult.Failure("L2 清洗将在后续版本接入")

    override suspend fun testConnection(): ConnectionTestResult =
        ConnectionTestResult(false, "尚未配置 LLM")
}
