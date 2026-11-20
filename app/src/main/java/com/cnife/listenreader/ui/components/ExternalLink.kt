package com.cnife.listenreader.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** 用系统浏览器打开外部链接（更新下载或 Release 页面）；没有可用浏览器时不阻断应用。 */
fun openExternalUrl(context: Context, url: String) {
    if (url.isBlank()) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // 设备没有浏览器：版本信息仍保留在设置页，用户可自行复制链接。
    }
}
