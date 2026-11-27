package com.cnife.listenreader.ui.player

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cnife.listenreader.ui.components.LrCard
import com.cnife.listenreader.ui.components.QuietAction
import com.cnife.listenreader.ui.theme.Ink

const val NOTIFICATION_PERMISSION_HINT = "通知未开启，锁屏控制不可用"

private const val PREFS_NAME = "listen_reader_ui"
private const val KEY_NOTIFICATION_ASKED = "notification_permission_asked"

data class NotificationPermissionUiState(
    val required: Boolean,
    val granted: Boolean,
    val onOpenSettings: () -> Unit = {},
)

fun isNotificationPermissionGranted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun rememberNotificationPermission(): NotificationPermissionUiState {
    val context = LocalContext.current
    val required = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    var granted by remember { mutableStateOf(isNotificationPermissionGranted(context)) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result -> granted = result }

    LaunchedEffect(required) {
        if (!required || granted) return@LaunchedEffect
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTIFICATION_ASKED, false)) return@LaunchedEffect
        prefs.edit().putBoolean(KEY_NOTIFICATION_ASKED, true).apply()
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, required) {
        if (!required) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = isNotificationPermissionGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return NotificationPermissionUiState(
        required = required,
        granted = granted,
        onOpenSettings = { openNotificationSettings(context) },
    )
}

@Composable
fun NotificationPermissionHint(
    state: NotificationPermissionUiState,
    modifier: Modifier = Modifier,
) {
    if (!state.required || state.granted) return
    LrCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("notification-permission-hint"),
        padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.NotificationsNone,
                contentDescription = null,
                tint = Ink,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = NOTIFICATION_PERMISSION_HINT,
                style = MaterialTheme.typography.bodyMedium,
                color = Ink,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            QuietAction(
                text = "开启",
                onClick = state.onOpenSettings,
                testTag = "notification-open-settings",
            )
        }
    }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
