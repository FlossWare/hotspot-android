package org.flossware.hotspot.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.flossware.hotspot.R
import org.flossware.hotspot.model.HotspotState

@Composable
fun ConnectionInfo(
    state: HotspotState,
    qrBitmap: Bitmap?,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = true,
) {
    var showPassword by remember { mutableStateOf(false) }

    CollapsibleSection(
        title = stringResource(R.string.connection_info),
        modifier = modifier,
        initiallyExpanded = initiallyExpanded,
    ) {
        CopyableInfoRow(
            label = stringResource(R.string.network_name),
            value = state.networkName,
        )

        ListItem(
            headlineContent = { Text(stringResource(R.string.password)) },
            supportingContent = {
                Text(
                    if (showPassword) state.passphrase
                    else "•".repeat(state.passphrase.length),
                )
            },
            trailingContent = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        imageVector = if (showPassword) Icons.Default.VisibilityOff
                        else Icons.Default.Visibility,
                        contentDescription = if (showPassword) {
                            stringResource(R.string.cd_hide_password)
                        } else {
                            stringResource(R.string.cd_show_password)
                        },
                    )
                }
            },
        )

        CopyableInfoRow(
            label = stringResource(R.string.socks_address),
            value = state.socksAddress,
        )

        CopyableInfoRow(
            label = stringResource(R.string.dns_address),
            value = state.dnsAddress,
        )

        if (qrBitmap != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.scan_to_connect),
                    modifier = Modifier.size(200.dp),
                )
                Text(
                    text = stringResource(R.string.scan_to_connect),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun CopyableInfoRow(label: String, value: String) {
    val context = LocalContext.current
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            Text(value, style = MaterialTheme.typography.bodyLarge)
        },
        trailingContent = {
            IconButton(onClick = { copyToClipboard(context, label, value) }) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.cd_copy_value, label),
                )
            }
        },
    )
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
}
