package com.cnife.listenreader.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cnife.listenreader.model.LibraryItem
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.ui.components.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    service: ReadingService,
    onNewCapture: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenResource: (String) -> Unit,
) {
    val state by service.library.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("听我读") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { padding ->
        if (state.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                Text(
                    text = "正在加载…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else if (state.items.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                EmptyState(
                    title = "还没有内容",
                    description = "把网页链接或一段文字加进来，听我读会把它变成可以收听的朗读文稿。",
                    actionLabel = "新建",
                    onAction = onNewCapture,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Button(onClick = onNewCapture, modifier = Modifier.fillMaxWidth()) {
                        Text("新建")
                    }
                }
                items(state.items, key = { it.id }) { item ->
                    LibraryRow(item = item, onClick = { onOpenResource(item.id) })
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(item: LibraryItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = item.title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = "${item.sourceLabel} · ${item.segmentCount} 段",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
