package org.flossware.hotspot.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.flossware.hotspot.R
import org.flossware.hotspot.model.HotspotState

@Composable
fun BluetoothInfo(
    state: HotspotState,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.bluetooth_status)) },
                supportingContent = {
                    Text(
                        if (state.bluetoothEnabled) stringResource(R.string.bluetooth_enabled)
                        else stringResource(R.string.bluetooth_disabled),
                    )
                },
                leadingContent = {
                    Icon(
                        Icons.Default.Bluetooth,
                        contentDescription = null,
                        tint = if (state.bluetoothEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )

            if (state.bluetoothEnabled && state.bluetoothDeviceName.isNotEmpty()) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.bluetooth_device_name)) },
                    supportingContent = {
                        Text(
                            state.bluetoothDeviceName,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                )
            }

            if (state.bluetoothConnectedDevices.isNotEmpty()) {
                ListItem(
                    headlineContent = {
                        Text("${stringResource(R.string.bluetooth_connected_devices)} (${state.bluetoothConnectedDevices.size})")
                    },
                )
                for (device in state.bluetoothConnectedDevices) {
                    ListItem(
                        headlineContent = { Text(device.deviceName) },
                        supportingContent = { Text(device.macAddress) },
                        leadingContent = {
                            Icon(Icons.Default.Bluetooth, contentDescription = null)
                        },
                    )
                }
            } else if (state.bluetoothEnabled) {
                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.no_bluetooth_devices),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }
    }
}
