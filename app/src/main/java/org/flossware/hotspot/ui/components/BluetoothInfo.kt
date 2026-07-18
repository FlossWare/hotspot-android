package org.flossware.hotspot.ui.components

import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
        val switchLabel = stringResource(
            R.string.cd_bluetooth_opt_in,
            if (state.bluetoothOptIn) stringResource(R.string.bluetooth_enabled)
            else stringResource(R.string.bluetooth_disabled),
        )

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
                    contentDescription = stringResource(R.string.cd_bluetooth_icon),
                    tint = if (state.bluetoothOptIn) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                Switch(
                    checked = state.bluetoothOptIn,
                    onCheckedChange = null,
                )
            },
            modifier = Modifier.toggleable(
                value = state.bluetoothOptIn,
                role = Role.Switch,
                onValueChange = { onBluetoothOptInChanged(it) },
            ),
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
                        Text(
                            "${stringResource(R.string.bluetooth_connected_devices)} ($deviceCount)",
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                            },
                        )
                    },
                )
                state.bluetoothConnectedDevices.forEach { device ->
                    ListItem(
                        headlineContent = { Text(device.deviceName) },
                        supportingContent = { Text(device.macAddress) },
                        leadingContent = {
                            Icon(
                                Icons.Default.Bluetooth,
                                contentDescription = stringResource(R.string.cd_bluetooth_device_icon),
                            )
                        },
                        modifier = Modifier.semantics(mergeDescendants = true) {},
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
