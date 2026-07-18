package org.flossware.hotspot.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.flossware.hotspot.R
import org.flossware.hotspot.model.HotspotState

@Composable
fun BluetoothInfo(
    state: HotspotState,
    onBluetoothOptInChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    val subtitle = if (state.bluetoothEnabled) stringResource(R.string.bluetooth_enabled)
    else stringResource(R.string.bluetooth_disabled)

    val deviceCount = state.bluetoothConnectedDevices.size

    CollapsibleSection(
        title = stringResource(R.string.bluetooth_status),
        subtitle = subtitle,
        badge = if (deviceCount > 0) "$deviceCount" else null,
        modifier = modifier,
        initiallyExpanded = initiallyExpanded,
    ) {
        ListItem(
            headlineContent = {
                Text(stringResource(R.string.bluetooth_transport))
            },
            supportingContent = {
                Text(stringResource(R.string.bluetooth_experimental_label))
            },
            leadingContent = {
                Icon(
                    Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = if (state.bluetoothOptIn) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                Switch(
                    checked = state.bluetoothOptIn,
                    onCheckedChange = onBluetoothOptInChanged,
                )
            },
        )

        if (state.bluetoothOptIn) {
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
                        Text("${stringResource(R.string.bluetooth_connected_devices)} ($deviceCount)")
                    },
                )
                state.bluetoothConnectedDevices.forEach { device ->
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
