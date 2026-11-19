package com.cnife.listenreader.ui.library

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.cnife.listenreader.model.LibraryItem

/** 删除前的确认对话框，明确说明会级联清理文稿、缓存音频与原文快照。 */
@Composable
fun DeleteResourceDialog(
    item: LibraryItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除这篇内容？") },
        text = {
            Text("将删除「${item.title}」的朗读文稿、缓存音频和原文快照，删除后无法恢复。")
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("delete-confirm"),
            ) {
                Text("删除")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("delete-cancel"),
            ) {
                Text("取消")
            }
        },
    )
}
