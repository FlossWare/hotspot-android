package org.flossware.hotspot.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
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
import org.flossware.hotspot.model.ConnectedDevice

@Composable
fun DeviceList(
    devices: List<ConnectedDevice>,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            ListItem(
                headlineContent = {
                    Text(
                        stringResource(R.string.connected_devices),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                trailingContent = {
                    Text(
                        "${devices.size}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
            )

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
}
