package com.cnife.listenreader.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.cnife.listenreader.ui.components.CardDivider
import com.cnife.listenreader.ui.components.LrCard
import com.cnife.listenreader.ui.components.QuietAction
import com.cnife.listenreader.ui.components.SectionLabel
import com.cnife.listenreader.ui.components.SegmentedControl
import com.cnife.listenreader.ui.components.openExternalUrl
import com.cnife.listenreader.ui.theme.Ink
import com.cnife.listenreader.ui.theme.Meta

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
    LrCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("update-section"),
        padding = PaddingValues(20.dp),
    ) {
        SettingsSectionTitle("版本")
        Text(
            text = "当前版本 v${update.currentVersion}",
            style = MaterialTheme.typography.bodySmall,
            color = Meta,
            modifier = Modifier
                .padding(top = 4.dp)
                .testTag("update-current"),
        )
        SectionLabel(
            text = "自动检查更新",
            modifier = Modifier.padding(top = 16.dp),
        )
        SegmentedControl(
            options = listOf("true" to "开启", "false" to "关闭"),
            selectedKey = if (update.autoCheck) "true" else "false",
            onSelect = { key -> onAutoCheckChange(key == "true") },
            modifier = Modifier.padding(top = 8.dp),
            tagPrefix = "update-auto",
        )
        QuietAction(
            text = if (update.check == UpdateCheckState.Checking) "检查中…" else "检查更新",
            onClick = onCheck,
            enabled = update.check != UpdateCheckState.Checking,
            modifier = Modifier.padding(top = 4.dp),
            testTag = "update-check",
        )
        when (val check = update.check) {
            UpdateCheckState.Idle, UpdateCheckState.Checking -> Unit
            is UpdateCheckState.Latest -> Text(
                text = "已是最新版本 v${check.version}",
                style = MaterialTheme.typography.bodySmall,
                color = Meta,
                modifier = Modifier.testTag("update-latest"),
            )
            is UpdateCheckState.Failed -> Text(
                text = check.message,
                style = MaterialTheme.typography.bodySmall,
                color = Meta,
                modifier = Modifier.testTag("update-check-error"),
            )
        }
        update.release?.let { release ->
            Spacer(Modifier.height(12.dp))
            CardDivider()
            Spacer(Modifier.height(12.dp))
            Text(
                text = "新版本 v${release.version}",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = Ink,
                modifier = Modifier.testTag("update-available"),
            )
            Text(
                text = release.notes.ifBlank { "该版本没有提供更新说明。" },
                style = MaterialTheme.typography.bodySmall,
                color = Meta,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .testTag("update-notes"),
            )
            if (update.ignored) {
                Text(
                    text = "已在资料库忽略该版本的提醒。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Meta,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .testTag("update-ignored"),
                )
            }
            QuietAction(
                text = "去更新",
                onClick = { onOpen(release.url) },
                modifier = Modifier.padding(top = 4.dp),
                testTag = "update-open",
            )
        }
    }
}
