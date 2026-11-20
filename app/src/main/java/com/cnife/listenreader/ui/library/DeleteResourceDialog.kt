package com.cnife.listenreader.ui.library

import androidx.compose.runtime.Composable
import com.cnife.listenreader.model.LibraryItem
import com.cnife.listenreader.ui.components.LrDialog

/** 删除前的确认弹层，明确说明会级联清理文稿、缓存音频与原文快照。 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun DeleteResourceDialog(
    item: LibraryItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    LrDialog(
        title = "删除这篇内容？",
        body = "会删除朗读文稿、缓存音频和原文快照，无法恢复。",
        confirmLabel = "删除",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        dismissLabel = "取消",
        confirmTag = "delete-confirm",
        dismissTag = "delete-cancel",
    )
}
