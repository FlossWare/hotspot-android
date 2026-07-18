package org.flossware.hotspot.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.flossware.hotspot.R
import org.flossware.hotspot.model.HotspotState

@Composable
fun UsbInfo(
    state: HotspotState,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    val subtitle = if (state.usbConnected) stringResource(R.string.usb_connected)
    else stringResource(R.string.usb_not_connected)

    CollapsibleSection(
        title = stringResource(R.string.usb_status),
        subtitle = subtitle,
        modifier = modifier,
        initiallyExpanded = initiallyExpanded,
    ) {
        ListItem(
            headlineContent = {
                Text(stringResource(R.string.usb_transport))
            },
            supportingContent = {
                Text(stringResource(R.string.usb_experimental_label))
            },
            leadingContent = {
                Icon(
                    Icons.Default.Usb,
                    contentDescription = null,
                    tint = if (state.usbConnected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )

        if (state.usbConnected) {
            ListItem(
                headlineContent = {
                    Text(
                        stringResource(R.string.usb_accessory_active),
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
            )
        }
    }
}
