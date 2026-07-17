package org.flossware.hotspot.client.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import org.flossware.hotspot.client.R
import org.flossware.hotspot.client.model.Transport
import org.flossware.hotspot.client.model.VpnState
import org.flossware.hotspot.client.viewmodel.BluetoothDeviceInfo
import org.flossware.hotspot.client.viewmodel.ClientViewModel

@Composable
fun ClientScreen(viewModel: ClientViewModel = viewModel()) {
    val state by viewModel.vpnState.collectAsState()
    var socksAddress by remember { mutableStateOf("${VpnState.DEFAULT_SOCKS_HOST}:${VpnState.DEFAULT_SOCKS_PORT}") }
    var selectedTransport by remember { mutableIntStateOf(0) }
    var selectedBtDevice by remember { mutableStateOf<BluetoothDeviceInfo?>(null) }
    var pairedDevices by remember { mutableStateOf<List<BluetoothDeviceInfo>>(emptyList()) }
    val context = LocalContext.current

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            if (selectedTransport == 0) {
                val (host, port) = parseAddress(socksAddress)
                viewModel.connect(host, port)
            } else {
                selectedBtDevice?.let { viewModel.connectBluetooth(it.address) }
            }
        }
    }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            pairedDevices = viewModel.getPairedDevices()
        }
    }

    fun hasBtPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    fun requestBtPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            btPermissionLauncher.launch(arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            ))
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.flossware_logo),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier
                    .width(200.dp)
                    .height(56.dp)
                    .padding(bottom = 8.dp),
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = selectedTransport == 0,
                    onClick = { selectedTransport = 0 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    enabled = !state.isConnected,
                    icon = { Icon(Icons.Default.Wifi, contentDescription = null) },
                ) {
                    Text(stringResource(R.string.transport_wifi_direct))
                }
                SegmentedButton(
                    selected = selectedTransport == 1,
                    onClick = {
                        selectedTransport = 1
                        if (hasBtPermissions()) {
                            pairedDevices = viewModel.getPairedDevices()
                        } else {
                            requestBtPermissions()
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    enabled = !state.isConnected,
                    icon = { Icon(Icons.Default.Bluetooth, contentDescription = null) },
                ) {
                    Text(stringResource(R.string.transport_bluetooth))
                }
            }

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (selectedTransport == 0) {
                        OutlinedTextField(
                            value = socksAddress,
                            onValueChange = { socksAddress = it },
                            label = { Text(stringResource(R.string.socks_server_label)) },
                            placeholder = { Text(stringResource(R.string.socks_server_hint)) },
                            singleLine = true,
                            enabled = !state.isConnected,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.paired_devices),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            IconButton(
                                onClick = {
                                    if (hasBtPermissions()) {
                                        pairedDevices = viewModel.getPairedDevices()
                                    } else {
                                        requestBtPermissions()
                                    }
                                },
                                enabled = !state.isConnected,
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                            }
                        }

                        if (pairedDevices.isEmpty()) {
                            Text(
                                stringResource(R.string.no_paired_devices),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        } else {
                            for (device in pairedDevices) {
                                ListItem(
                                    headlineContent = { Text(device.name) },
                                    supportingContent = { Text(device.address) },
                                    leadingContent = {
                                        RadioButton(
                                            selected = selectedBtDevice?.address == device.address,
                                            onClick = { selectedBtDevice = device },
                                            enabled = !state.isConnected,
                                        )
                                    },
                                    modifier = Modifier.clickable(enabled = !state.isConnected) {
                                        selectedBtDevice = device
                                    },
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = if (state.isConnected) {
                            val transport = if (state.transport == Transport.BLUETOOTH) "Bluetooth" else "Wi-Fi Direct"
                            "${stringResource(R.string.connected)} ($transport)"
                        } else {
                            stringResource(R.string.disconnected)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (state.isConnected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Button(
                onClick = {
                    if (state.isConnected) {
                        viewModel.disconnect()
                    } else {
                        val vpnIntent = viewModel.prepareVpn()
                        if (vpnIntent != null) {
                            vpnLauncher.launch(vpnIntent)
                        } else {
                            if (selectedTransport == 0) {
                                val (host, port) = parseAddress(socksAddress)
                                viewModel.connect(host, port)
                            } else {
                                selectedBtDevice?.let { viewModel.connectBluetooth(it.address) }
                            }
                        }
                    }
                },
                enabled = state.isConnected || selectedTransport == 0 || selectedBtDevice != null,
                colors = if (state.isConnected) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text(
                    text = if (state.isConnected) stringResource(R.string.disconnect)
                        else stringResource(R.string.connect),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            state.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun parseAddress(address: String): Pair<String, Int> {
    val parts = address.split(":")
    val host = parts.getOrElse(0) { VpnState.DEFAULT_SOCKS_HOST }
    val port = parts.getOrElse(1) { "${VpnState.DEFAULT_SOCKS_PORT}" }.toIntOrNull() ?: VpnState.DEFAULT_SOCKS_PORT
    return host to port
}
