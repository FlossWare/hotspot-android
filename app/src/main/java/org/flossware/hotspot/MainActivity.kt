package org.flossware.hotspot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.flossware.hotspot.diagnostics.DiagnosticsManager
import org.flossware.hotspot.service.HotspotService
import org.flossware.hotspot.ui.DiagnosticsScreen
import org.flossware.hotspot.ui.HotspotScreen
import org.flossware.hotspot.ui.theme.HotspotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HotspotTheme {
                var showDiagnostics by remember { mutableStateOf(false) }
                val diagnosticsManager = remember {
                    HotspotService.diagnosticsManager ?: DiagnosticsManager(this@MainActivity)
                }

                if (showDiagnostics) {
                    DiagnosticsScreen(
                        diagnosticsManager = diagnosticsManager,
                        onBack = { showDiagnostics = false },
                    )
                } else {
                    HotspotScreen(
                        onNavigateToDiagnostics = { showDiagnostics = true },
                    )
                }
            }
        }
    }
}
