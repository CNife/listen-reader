package com.cnife.listenreader

import android.app.Application
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
    ListenReaderTheme(darkTheme = darkTheme) {
        AppNavHost(service = service)
    }
}
