package com.cnife.listenreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.service.UpdateCheckState
import com.cnife.listenreader.service.UpdateState
import com.cnife.listenreader.ui.components.QuietAction
import com.cnife.listenreader.ui.components.RuleLine
import com.cnife.listenreader.ui.components.TextTabs
import com.cnife.listenreader.ui.components.openExternalUrl
import com.cnife.listenreader.ui.theme.Ink
import com.cnife.listenreader.ui.theme.Ink2
import com.cnife.listenreader.ui.theme.Ink3

/** 设置页版本区：自动检查开关、手动检查与已发现的更新。 */
@Composable
fun UpdateSection(service: ReadingService) {
    val state by service.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    UpdateContent(
        update = state.update,
        onAutoCheckChange = service::setAutoCheckUpdate,
        onCheck = service::checkForUpdate,
        onOpen = { url -> openExternalUrl(context, url) },
    )
}

@Composable
internal fun UpdateContent(
    update: UpdateState,
    onAutoCheckChange: (Boolean) -> Unit,
    onCheck: () -> Unit,
    onOpen: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("update-section"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SettingsSectionTitle("版本")
        Spacer(Modifier.height(4.dp))
        Text(
            text = "当前版本 v${update.currentVersion}",
            style = MaterialTheme.typography.bodySmall,
            color = Ink2,
            modifier = Modifier.testTag("update-current"),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "自动检查更新",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Ink2,
        )
        TextTabs(
            options = listOf("true" to "开启", "false" to "关闭"),
            selectedKey = if (update.autoCheck) "true" else "false",
            onSelect = { key -> onAutoCheckChange(key == "true") },
            tagPrefix = "update-auto",
        )
        QuietAction(
            text = if (update.check == UpdateCheckState.Checking) "检查中…" else "检查更新",
            onClick = onCheck,
            enabled = update.check != UpdateCheckState.Checking,
            testTag = "update-check",
        )
        when (val check = update.check) {
            UpdateCheckState.Idle, UpdateCheckState.Checking -> Unit
            is UpdateCheckState.Latest -> Text(
                text = "已是最新版本 v${check.version}",
                style = MaterialTheme.typography.bodySmall,
                color = Ink2,
                modifier = Modifier.testTag("update-latest"),
            )
            is UpdateCheckState.Failed -> Text(
                text = check.message,
                style = MaterialTheme.typography.bodySmall,
                color = Ink2,
                modifier = Modifier.testTag("update-check-error"),
            )
        }
        update.release?.let { release ->
            RuleLine()
            Spacer(Modifier.height(8.dp))
            Text(
                text = "新版本 v${release.version}",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Ink,
                modifier = Modifier.testTag("update-available"),
            )
            Text(
                text = release.notes.ifBlank { "该版本没有提供更新说明。" },
                style = MaterialTheme.typography.bodySmall,
                color = Ink2,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("update-notes"),
            )
            if (update.ignored) {
                Text(
                    text = "已在资料库忽略该版本的提醒。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink3,
                    modifier = Modifier.testTag("update-ignored"),
                )
            }
            QuietAction(
                text = "去更新",
                onClick = { onOpen(release.url) },
                testTag = "update-open",
            )
        }
    }
}
