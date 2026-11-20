package com.cnife.listenreader

import android.app.Activity
import android.app.Application
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cnife.listenreader.di.AppContainer
import com.cnife.listenreader.model.ThemeMode
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.ui.nav.AppNavHost
import com.cnife.listenreader.ui.theme.ListenReaderTheme

class ListenReaderApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

@Composable
fun ListenReaderApp(service: ReadingService) {
    val settings by service.settings.collectAsStateWithLifecycle()
    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    ListenReaderTheme(darkTheme = darkTheme) {
        AppNavHost(service = service)
    }
}
