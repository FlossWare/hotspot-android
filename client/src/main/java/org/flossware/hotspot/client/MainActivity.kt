package org.flossware.hotspot.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.flossware.hotspot.client.ui.ClientScreen
import org.flossware.hotspot.client.ui.theme.ClientTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClientTheme {
                ClientScreen()
            }
        }
    }
}
