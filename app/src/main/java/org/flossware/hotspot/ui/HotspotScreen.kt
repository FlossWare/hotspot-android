package org.flossware.hotspot.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import org.flossware.hotspot.service.HotspotService
import org.flossware.hotspot.service.WifiDirectManager
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
fun HotspotScreen(
    viewModel: HotspotViewModel = viewModel(),
    onNavigateToDiagnostics: () -> Unit = {},
) {
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
            HotspotService.updatePermissionsDenied(true)
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
                    IconButton(onClick = onNavigateToDiagnostics) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.cd_diagnostics),
                        )
                    }
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

            // Feature unavailability warnings (shown before starting)
            if (!state.isRunning && !state.wifiDirectAvailable) {
                FeatureWarningCard(
                    message = stringResource(R.string.error_wifi_direct_unavailable_suggestion),
                )
            }

            if (!state.isRunning && !state.mobileDataAvailable) {
                FeatureWarningCard(
                    message = stringResource(R.string.error_mobile_data_guidance),
                )
            }

            // Permission denied banner with Open Settings button
            if (state.permissionsDenied && !state.isRunning) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.error_permissions_denied),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            Text(
                                text = stringResource(
                                    R.string.error_permission_wifi_direct,
                                    "Nearby Devices",
                                ),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        } else {
                            Text(
                                text = stringResource(
                                    R.string.error_permission_wifi_direct,
                                    "Location",
                                ),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                )
                                context.startActivity(intent)
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text(stringResource(R.string.open_settings))
                        }
                    }
                }
            }

            state.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )

                // Offer Bluetooth-only fallback when Wi-Fi Direct fails
                if (!state.isRunning && state.bluetoothAvailable && !state.wifiDirectAvailable) {
                    Button(
                        onClick = { viewModel.startBluetoothOnly() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    ) {
                        Text(stringResource(R.string.continue_bluetooth_only))
                    }
                }
            }

            // Passphrase configuration (only when not running, API 29+)
            if (!state.isRunning) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val passphraseValue = state.configuredPassphrase
                    val isError = passphraseValue.length < WifiDirectManager.MIN_PASSPHRASE_LENGTH
                    OutlinedTextField(
                        value = passphraseValue,
                        onValueChange = { viewModel.setPassphrase(it) },
                        label = { Text(stringResource(R.string.passphrase_label)) },
                        placeholder = { Text(stringResource(R.string.passphrase_hint)) },
                        isError = isError && passphraseValue.isNotEmpty(),
                        supportingText = if (isError && passphraseValue.isNotEmpty()) {
                            { Text(stringResource(R.string.passphrase_error_too_short)) }
                        } else {
                            null
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.passphrase_note_legacy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HotspotToggle(
                isRunning = state.isRunning,
                onStart = ::onStartRequested,
                onStop = { viewModel.stopHotspot() },
            )

            // Bluetooth-only mode banner
            if (state.isRunning && state.bluetoothOnlyMode) {
                FeatureWarningCard(
                    message = stringResource(R.string.bluetooth_only_mode_active),
                )
            }

            if (state.isRunning) {
                val qrBitmap = remember(state.networkName, state.passphrase) {
                    viewModel.generateQrBitmap(state)
                }

                if (!state.bluetoothOnlyMode) {
                    ConnectionInfo(state = state, qrBitmap = qrBitmap)
                }

                DeviceList(devices = state.connectedDevices)

                if (state.bluetoothAvailable) {
                    BluetoothInfo(
                        state = state,
                        onBluetoothOptInChanged = { enabled ->
                            viewModel.setBluetoothOptIn(enabled)
                        },
                    )
                }

                if (state.usbAvailable) {
                    UsbInfo(state = state)
                }

                ProxyStats(state = state)

                CompatibilityTips()

                if (!state.bluetoothOnlyMode) {
                    SetupInstructions(state = state)
                }
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
            dismissButton = {
                TextButton(onClick = {
                    showRationale = false
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    )
                    context.startActivity(intent)
                }) {
                    Text(stringResource(R.string.open_settings))
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

@Composable
private fun FeatureWarningCard(
    message: String,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(end = 12.dp),
            )
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
