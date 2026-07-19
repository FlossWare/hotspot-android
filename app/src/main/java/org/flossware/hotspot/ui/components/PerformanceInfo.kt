package org.flossware.hotspot.ui.components

import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.flossware.hotspot.R
import org.flossware.hotspot.metrics.MetricsSnapshot

/**
 * Expandable section showing runtime performance metrics: CPU, memory,
 * throughput, active connections, and session counters.
 */
@Composable
fun PerformanceInfo(
    snapshot: MetricsSnapshot,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    val hasMetrics = snapshot.cpuUtilization > 0f ||
        snapshot.heapUsedBytes > 0L ||
        snapshot.throughputKbps > 0f ||
        snapshot.activeSocksConnections > 0 ||
        snapshot.dnsQueries > 0L ||
        snapshot.reconnectCount > 0 ||
        snapshot.totalErrors > 0

    if (!hasMetrics) return

    CollapsibleSection(
        title = stringResource(R.string.performance_title),
        modifier = modifier,
        initiallyExpanded = initiallyExpanded,
    ) {
        if (snapshot.throughputKbps > 0f) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.perf_throughput)) },
                supportingContent = {
                    Text(formatThroughput(snapshot.throughputKbps))
                },
            )
        }

        if (snapshot.activeSocksConnections > 0) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.perf_active_connections)) },
                supportingContent = { Text("${snapshot.activeSocksConnections}") },
            )
        }

        if (snapshot.dnsQueries > 0L) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.perf_dns_queries)) },
                supportingContent = {
                    val hitRate = if (snapshot.dnsQueries > 0) {
                        (snapshot.dnsCacheHits * 100 / snapshot.dnsQueries).toInt()
                    } else {
                        0
                    }
                    Text("${snapshot.dnsQueries} ($hitRate% cache hit)")
                },
            )
        }

        if (snapshot.cpuUtilization > 0f) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.perf_cpu)) },
                supportingContent = {
                    Text("%.1f%%".format(snapshot.cpuUtilization * 100))
                },
            )
        }

        if (snapshot.heapUsedBytes > 0L) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.perf_memory)) },
                supportingContent = {
                    val used = formatBytes(snapshot.heapUsedBytes)
                    val max = formatBytes(snapshot.heapMaxBytes)
                    Text("$used / $max")
                },
            )
        }

        if (snapshot.nativeHeapBytes > 0L) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.perf_native_memory)) },
                supportingContent = { Text(formatBytes(snapshot.nativeHeapBytes)) },
            )
        }

        if (snapshot.batteryConsumedMah > 0f) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.perf_battery)) },
                supportingContent = {
                    Text("%.1f mAh".format(snapshot.batteryConsumedMah))
                },
            )
        }

        if (snapshot.reconnectCount > 0) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.perf_reconnects)) },
                supportingContent = { Text("${snapshot.reconnectCount}") },
            )
        }

        if (snapshot.totalErrors > 0) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.perf_errors)) },
                supportingContent = { Text("${snapshot.totalErrors}") },
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

internal fun formatThroughput(kbps: Float): String = when {
    kbps < 1 -> "%.1f bps".format(kbps * 1000)
    kbps < 1000 -> "%.1f kbps".format(kbps)
    else -> "%.1f Mbps".format(kbps / 1000)
}
