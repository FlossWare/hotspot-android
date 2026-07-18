package org.flossware.hotspot.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.flossware.hotspot.R
import org.flossware.hotspot.model.ConnectedDevice

@Composable
fun DeviceList(
    devices: List<ConnectedDevice>,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = true,
) {
    CollapsibleSection(
        title = stringResource(R.string.connected_devices),
        badge = if (devices.isNotEmpty()) "${devices.size}" else null,
        modifier = modifier,
        initiallyExpanded = initiallyExpanded,
    ) {
        if (devices.isEmpty()) {
            ListItem(
                headlineContent = {
                    Text(
                        stringResource(R.string.no_devices_connected),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        } else {
            devices.forEach { device ->
                ListItem(
                    headlineContent = { Text(device.deviceName) },
                    supportingContent = { Text(device.macAddress) },
                    leadingContent = {
                        Icon(Icons.Default.PhoneAndroid, contentDescription = null)
                    },
                )
            }
        }
    }
}
