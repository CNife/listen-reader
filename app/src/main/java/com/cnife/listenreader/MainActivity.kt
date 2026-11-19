package com.cnife.listenreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cnife.listenreader.service.ReadingService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val service: ReadingService = (application as ListenReaderApp).container.readingService
        setContent {
            ListenReaderApp(service)
        }
    }
}
