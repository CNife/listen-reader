package com.cnife.listenreader.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cnife.listenreader.ui.theme.Ink
import com.cnife.listenreader.ui.theme.Muted

@Composable
fun EmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    LrCard(
        modifier = modifier.fillMaxWidth(),
        padding = androidx.compose.foundation.layout.PaddingValues(24.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = Ink,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(4.dp))
            QuietAction(text = actionLabel, onClick = onAction)
        }
    }
}
