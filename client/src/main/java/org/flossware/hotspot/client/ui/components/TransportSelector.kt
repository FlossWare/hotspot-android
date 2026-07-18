package org.flossware.hotspot.client.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.flossware.hotspot.client.R

@Composable
fun TransportSelector(
    selectedTransport: Int,
    onTransportSelected: (Int) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = selectedTransport == 0,
            onClick = { onTransportSelected(0) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            enabled = enabled,
            icon = { Icon(Icons.Default.Wifi, contentDescription = null) },
        ) {
            Text(stringResource(R.string.transport_wifi_direct))
        }
        SegmentedButton(
            selected = selectedTransport == 1,
            onClick = { onTransportSelected(1) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            enabled = enabled,
            icon = { Icon(Icons.Default.Bluetooth, contentDescription = null) },
        ) {
            Text(stringResource(R.string.transport_bluetooth))
        }
    }
}
