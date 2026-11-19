package com.cnife.listenreader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cnife.listenreader.service.ReadingService

class MainActivity : ComponentActivity() {

    private lateinit var readingService: ReadingService

    /** 配置变更重建 Activity 时不重复进入采集。 */
    private var shareIntentHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readingService = (application as ListenReaderApp).container.readingService
        setContent {
            ListenReaderApp(readingService)
        }
        shareIntentHandled = savedInstanceState?.getBoolean(STATE_SHARE_HANDLED) == true
        if (!shareIntentHandled) handleShareIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SHARE_HANDLED, shareIntentHandled)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    /** 系统分享（text/plain）进入采集：首个 http(s) URL 走网页模式，否则走文本模式。 */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.type?.startsWith("text/") != true) return
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)
        if (text.isNullOrBlank()) return
        readingService.handleShareIntent(text)
        shareIntentHandled = true
    }

    private companion object {
        const val STATE_SHARE_HANDLED = "share_intent_handled"
    }
}
