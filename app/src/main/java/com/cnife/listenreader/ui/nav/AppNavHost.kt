package com.cnife.listenreader.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cnife.listenreader.service.CaptureMode
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.ui.capture.CaptureScreen
import com.cnife.listenreader.ui.capture.PreviewScreen
import com.cnife.listenreader.ui.library.LibraryScreen
import com.cnife.listenreader.ui.player.PlayerScreen
import com.cnife.listenreader.ui.settings.SettingsScreen

object Routes {
    const val LIBRARY = "library"
    const val CAPTURE = "capture"
    const val PREVIEW = "preview"
    const val SETTINGS = "settings"
    const val PLAYER = "player/{resourceId}"

    fun player(resourceId: String) = "player/$resourceId"
}

@Composable
fun AppNavHost(service: ReadingService) {
    val navController = rememberNavController()
    val capture by service.capture.collectAsStateWithLifecycle()

    // 分享/新建/粘贴文本会开启新的采集会话（sessionId 自增）；每次提取出新预览
    // （previewRevision 自增）都导航到预览。返回采集页后再次提取也能重新进入预览，
    // 且不会因返回操作本身重复入栈。
    LaunchedEffect(capture.sessionId, capture.previewRevision) {
        if (capture.mode == CaptureMode.IDLE) return@LaunchedEffect
        val target = if (capture.preview != null) Routes.PREVIEW else Routes.CAPTURE
        if (navController.currentDestination?.route != target) {
            navController.navigate(target) {
                launchSingleTop = true
                if (target == Routes.PREVIEW) {
                    popUpTo(Routes.CAPTURE) { inclusive = false }
                }
            }
        }
    }

    NavHost(navController = navController, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            LibraryScreen(
                service = service,
                onNewCapture = { service.beginUrlCapture() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenResource = { resourceId ->
                    service.openResource(resourceId)
                    navController.navigate(Routes.player(resourceId))
                },
            )
        }
        composable(Routes.CAPTURE) {
            CaptureScreen(
                service = service,
                onBack = {
                    service.discardCapture()
                    service.releasePageView()
                    navController.popBackStack()
                },
            )
        }
        composable(Routes.PREVIEW) {
            PreviewScreen(
                service = service,
                onBack = { navController.popBackStack() },
                onSaved = { resourceId ->
                    service.openResource(resourceId)
                    navController.navigate(Routes.player(resourceId)) {
                        popUpTo(Routes.LIBRARY) { inclusive = false }
                    }
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                service = service,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.PLAYER,
            arguments = listOf(navArgument("resourceId") { type = NavType.StringType }),
        ) { entry ->
            PlayerScreen(
                service = service,
                resourceId = entry.arguments?.getString("resourceId").orEmpty(),
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
    }
}
