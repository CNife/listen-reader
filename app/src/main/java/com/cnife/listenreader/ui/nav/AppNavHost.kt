package com.cnife.listenreader.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
    const val READER = "reader/{resourceId}"

    fun player(resourceId: String) = "player/$resourceId"
    fun reader(resourceId: String) = "reader/$resourceId"
}

@Composable
fun AppNavHost(service: ReadingService) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            LibraryScreen(
                service = service,
                onNewCapture = {
                    service.beginUrlCapture()
                    navController.navigate(Routes.CAPTURE)
                },
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
                onBack = { navController.popBackStack() },
                onOpenPreview = { navController.navigate(Routes.PREVIEW) },
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
                onOpenReader = { resourceId ->
                    navController.navigate(Routes.reader(resourceId))
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = Routes.READER,
            arguments = listOf(navArgument("resourceId") { type = NavType.StringType }),
        ) { entry ->
            PlayerScreen(
                service = service,
                resourceId = entry.arguments?.getString("resourceId").orEmpty(),
                onBack = { navController.popBackStack() },
                onOpenReader = {},
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
    }
}
