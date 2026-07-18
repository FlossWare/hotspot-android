package org.flossware.hotspot.client.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.flossware.hotspot.client.R
import org.flossware.hotspot.client.model.Transport
import org.flossware.hotspot.client.viewmodel.BluetoothDeviceInfo

@Composable
fun ConnectionForm(
    selectedTransport: Int,
    socksAddress: String,
    onAddressChange: (String) -> Unit,
    isConnected: Boolean,
    connectedTransport: Transport,
    pairedDevices: List<BluetoothDeviceInfo>,
    selectedBtDevice: BluetoothDeviceInfo?,
    onDeviceSelected: (BluetoothDeviceInfo) -> Unit,
    onRefreshDevices: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    connectEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (selectedTransport == 0) {
                WifiDirectForm(
                    socksAddress = socksAddress,
                    onAddressChange = onAddressChange,
                    enabled = !isConnected,
                )
            } else {
                BluetoothDeviceList(
                    devices = pairedDevices,
                    selectedDevice = selectedBtDevice,
                    onDeviceSelected = onDeviceSelected,
                    onRefresh = onRefreshDevices,
                    enabled = !isConnected,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            ConnectionStatus(
                isConnected = isConnected,
                transport = connectedTransport,
            )
        }
    }

    Spacer(modifier = Modifier.height(4.dp))

    ConnectButton(
        isConnected = isConnected,
        enabled = connectEnabled,
        onConnect = onConnect,
        onDisconnect = onDisconnect,
    )
}

@Composable
private fun WifiDirectForm(
    socksAddress: String,
    onAddressChange: (String) -> Unit,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = socksAddress,
        onValueChange = onAddressChange,
        label = { Text(stringResource(R.string.socks_server_label)) },
        placeholder = { Text(stringResource(R.string.socks_server_hint)) },
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BluetoothDeviceList(
    devices: List<BluetoothDeviceInfo>,
    selectedDevice: BluetoothDeviceInfo?,
    onDeviceSelected: (BluetoothDeviceInfo) -> Unit,
    onRefresh: () -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.paired_devices),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        IconButton(onClick = onRefresh, enabled = enabled) {
            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
        }
    }

    if (devices.isEmpty()) {
        Text(
            stringResource(R.string.no_paired_devices),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    } else {
        for (device in devices) {
            ListItem(
                headlineContent = { Text(device.name) },
                supportingContent = { Text(device.address) },
                leadingContent = {
                    RadioButton(
                        selected = selectedDevice?.address == device.address,
                        onClick = { onDeviceSelected(device) },
                        enabled = enabled,
                    )
                },
                modifier = Modifier
                    .semantics(mergeDescendants = true) {}
                    .clickable(enabled = enabled) {
                        onDeviceSelected(device)
                    },
            )
        }
    }
}

@Composable
private fun ConnectionStatus(
    isConnected: Boolean,
    transport: Transport,
) {
    val statusText = if (isConnected) {
        val transportName = if (transport == Transport.BLUETOOTH) {
            stringResource(R.string.transport_bluetooth)
        } else {
            stringResource(R.string.transport_wifi_direct)
        }
        "${stringResource(R.string.connected)} ($transportName)"
    } else {
        stringResource(R.string.disconnected)
    }

    Text(
        text = statusText,
        style = MaterialTheme.typography.titleMedium,
        color = if (isConnected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
            },
    )
}

@Composable
private fun ConnectButton(
    isConnected: Boolean,
    enabled: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Button(
        onClick = if (isConnected) onDisconnect else onConnect,
        enabled = enabled,
        colors = if (isConnected) {
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.buttonColors()
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Text(
            text = if (isConnected) stringResource(R.string.disconnect)
            else stringResource(R.string.connect),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
