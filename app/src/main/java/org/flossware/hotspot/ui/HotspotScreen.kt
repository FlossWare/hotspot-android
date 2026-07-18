package org.flossware.hotspot.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import org.flossware.hotspot.R
import org.flossware.hotspot.log.LogExporter
import org.flossware.hotspot.ui.components.BluetoothInfo
import org.flossware.hotspot.ui.components.CacheInfo
import org.flossware.hotspot.ui.components.CompatibilityTips
import org.flossware.hotspot.ui.components.ConnectionInfo
import org.flossware.hotspot.ui.components.DeviceList
import org.flossware.hotspot.ui.components.HotspotToggle
import org.flossware.hotspot.ui.components.ProxyStats
import org.flossware.hotspot.ui.components.SetupInstructions
import org.flossware.hotspot.ui.components.UsbInfo
import org.flossware.hotspot.viewmodel.HotspotViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotspotScreen(viewModel: HotspotViewModel = viewModel()) {
    val state by viewModel.hotspotState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showRationale by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) {
            viewModel.startHotspot()
        } else {
            showRationale = true
        }
    }

    fun requiredPermissions(): Array<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        return perms.toTypedArray()
    }

    fun hasPermissions(): Boolean {
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun onStartRequested() {
        if (hasPermissions()) {
            viewModel.startHotspot()
        } else {
            permissionLauncher.launch(requiredPermissions())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                actions = {
                    IconButton(onClick = {
                        val info = buildString {
                            appendLine("Status: ${if (state.isRunning) "Running" else "Stopped"}")
                            if (state.isRunning) {
                                appendLine("Transport: Wi-Fi Direct")
                                appendLine("SOCKS Address: ${state.socksAddress}")
                                appendLine("DNS Address: ${state.dnsAddress}")
                                appendLine("Connected Devices: ${state.connectedDevices.size}")
                                appendLine("Bluetooth: ${if (state.bluetoothEnabled) "Enabled" else "Disabled"}")
                                appendLine("USB: ${if (state.usbConnected) "Connected" else "Not connected"}")
                                appendLine("Bytes Transferred: ${state.bytesTransferred}")
                                appendLine("Uptime: ${state.uptimeSeconds}s")
                            }
                        }
                        LogExporter.shareLogs(context, info)
                    }) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = stringResource(R.string.cd_share_logs),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.flossware_logo),
                contentDescription = stringResource(R.string.cd_flossware_logo),
                modifier = Modifier
                    .width(200.dp)
                    .height(56.dp)
                    .padding(bottom = 8.dp),
            )

            state.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            HotspotToggle(
                isRunning = state.isRunning,
                onStart = ::onStartRequested,
                onStop = { viewModel.stopHotspot() },
            )

            if (state.isRunning) {
                val qrBitmap = remember(state.networkName, state.passphrase) {
                    viewModel.generateQrBitmap(state)
                }

                ConnectionInfo(state = state, qrBitmap = qrBitmap)

                DeviceList(devices = state.connectedDevices)

                BluetoothInfo(
                    state = state,
                    onBluetoothOptInChanged = { enabled ->
                        viewModel.setBluetoothOptIn(enabled)
                    },
                )

                UsbInfo(state = state)

                ProxyStats(state = state)

                CompatibilityTips()

                SetupInstructions(state = state)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text(stringResource(R.string.permission_rationale_title)) },
            text = {
                Text(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        stringResource(R.string.permission_rationale_nearby)
                    } else {
                        stringResource(R.string.permission_rationale_location)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    permissionLauncher.launch(requiredPermissions())
                }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }

    LaunchedEffect(state.error) {
        state.error?.let { error ->
            snackbarHostState.showSnackbar(error)
        }
    }
}
