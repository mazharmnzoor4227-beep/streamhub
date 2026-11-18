package com.streamhub.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.streamhub.app.ui.StreamHubApp
import com.streamhub.app.ui.theme.StreamHubTheme
import com.streamhub.app.ui.theme.Night

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StreamHubTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().background(Night),
                    color = Night
                ) {
                    StreamHubApp()
                }
            }
        }
    }
}
