package org.flossware.hotspot.ui.components

import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.flossware.hotspot.R
import org.flossware.hotspot.model.HotspotState

@Composable
fun ProxyStats(
    state: HotspotState,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    val hasStats = state.bytesTransferred > 0 || state.dnsCacheHits > 0 ||
        state.httpCacheHits > 0 || state.dataSaved > 0
    if (!hasStats) return

    CollapsibleSection(
        title = stringResource(R.string.statistics),
        modifier = modifier,
        initiallyExpanded = initiallyExpanded,
    ) {
        if (state.bytesTransferred > 0) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.data_transferred)) },
                supportingContent = { Text(formatBytes(state.bytesTransferred)) },
            )
        }

        if (state.dnsCacheHits > 0) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.dns_cache_hits)) },
                supportingContent = { Text("${state.dnsCacheHits}") },
            )
        }

        if (state.httpCacheHits > 0) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.http_cache_hits)) },
                supportingContent = { Text("${state.httpCacheHits}") },
            )
        }

        if (state.dataSaved > 0) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.data_saved)) },
                supportingContent = { Text(formatBytes(state.dataSaved)) },
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
