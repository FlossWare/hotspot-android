package org.flossware.hotspot.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.flossware.hotspot.R
import org.flossware.hotspot.model.HotspotState

@Composable
fun ProxyInfo(
    state: HotspotState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showPassword by remember { mutableStateOf(false) }

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            InfoRow(
                label = stringResource(R.string.network_name),
                value = state.networkName,
                context = context,
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.password)) },
                supportingContent = {
                    Text(if (showPassword) state.passphrase else "•".repeat(state.passphrase.length))
                },
                trailingContent = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            imageVector = if (showPassword) Icons.Default.VisibilityOff
                            else Icons.Default.Visibility,
                            contentDescription = null,
                        )
                    }
                },
            )
            InfoRow(
                label = stringResource(R.string.socks_address),
                value = state.socksAddress,
                context = context,
            )
            InfoRow(
                label = stringResource(R.string.dns_address),
                value = state.dnsAddress,
                context = context,
            )
            if (state.bytesTransferred > 0) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.data_transferred)) },
                    supportingContent = { Text(formatBytes(state.bytesTransferred)) },
                )
            }
            if (state.uptimeSeconds > 0) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.uptime)) },
                    supportingContent = {
                        Text(
                            formatUptime(state.uptimeSeconds) +
                            if (state.isIdle) stringResource(R.string.idle_suffix) else "",
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, context: Context) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            Text(value, style = MaterialTheme.typography.bodyLarge)
        },
        trailingContent = {
            IconButton(onClick = { copyToClipboard(context, label, value) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
            }
        },
    )
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}

internal fun formatUptime(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
