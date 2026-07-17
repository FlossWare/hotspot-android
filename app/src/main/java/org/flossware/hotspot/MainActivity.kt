package org.flossware.hotspot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.flossware.hotspot.ui.HotspotScreen
import org.flossware.hotspot.ui.theme.HotspotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HotspotTheme {
                HotspotScreen()
            }
        }
    }
}
