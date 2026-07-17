package org.flossware.hotspot.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.flossware.hotspot.R
import org.flossware.hotspot.model.HotspotState

@Composable
fun CacheInfo(
    state: HotspotState,
    modifier: Modifier = Modifier,
) {
    val hasStats = state.dnsCacheHits > 0 || state.httpCacheHits > 0 || state.dataSaved > 0
    if (!hasStats) return

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.cache_stats)) },
                leadingContent = {
                    Icon(Icons.Default.Cached, contentDescription = null)
                },
            )

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
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
