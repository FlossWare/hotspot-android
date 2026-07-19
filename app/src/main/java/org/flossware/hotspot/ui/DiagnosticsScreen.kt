package org.flossware.hotspot.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.flossware.hotspot.R
import org.flossware.hotspot.diagnostics.ConnectionPhase
import org.flossware.hotspot.diagnostics.DiagnosticsManager
import org.flossware.hotspot.diagnostics.SessionSummary
import org.flossware.hotspot.model.HotspotState
import org.flossware.hotspot.service.HotspotService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    diagnosticsManager: DiagnosticsManager,
    onBack: () -> Unit,
) {
    val state by HotspotService.state.collectAsState()
    var refreshTick by remember { mutableLongStateOf(0L) }

    // Refresh metrics every 2 seconds while the screen is visible
    LaunchedEffect(Unit) {
        while (true) {
            delay(REFRESH_INTERVAL_MS)
            refreshTick++
        }
    }

    // Read session history on each tick
    val sessions = remember(refreshTick) {
        diagnosticsManager.getSessionHistory().getSessions()
    }

    val latestEvent = remember(refreshTick) {
        diagnosticsManager.getLatestEvent()
    }

    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_navigate_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Current session card
            CurrentSessionCard(state = state, phase = latestEvent?.phase)

            // Share diagnostics button
            Button(
                onClick = {
                    val report = diagnosticsManager.exportReport()
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_TEXT, report)
                        putExtra(Intent.EXTRA_SUBJECT, "FlossWare Hotspot Diagnostics")
                    }
                    val chooser = Intent.createChooser(intent, null)
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(chooser)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(stringResource(R.string.share_diagnostics))
            }

            // Session history card
            if (sessions.isNotEmpty()) {
                SessionHistoryCard(sessions = sessions)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CurrentSessionCard(
    state: HotspotState,
    phase: ConnectionPhase?,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.current_session),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))

            val transport = when {
                state.bluetoothOnlyMode -> stringResource(R.string.transport_bluetooth)
                state.isRunning -> stringResource(R.string.transport_wifi_direct)
                else -> stringResource(R.string.transport_none)
            }

            ListItem(
                headlineContent = { Text(stringResource(R.string.transport_label)) },
                supportingContent = { Text(transport) },
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.phase_label)) },
                supportingContent = {
                    Text(phase?.name ?: stringResource(R.string.phase_idle))
                },
            )

            if (state.isRunning) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.uptime)) },
                    supportingContent = { Text(formatDuration(state.uptimeSeconds)) },
                )

                ListItem(
                    headlineContent = { Text(stringResource(R.string.data_transferred)) },
                    supportingContent = { Text(formatBytes(state.bytesTransferred)) },
                )

                val totalDevices = state.connectedDevices.size +
                    state.bluetoothConnectedDevices.size
                ListItem(
                    headlineContent = { Text(stringResource(R.string.active_connections)) },
                    supportingContent = { Text("$totalDevices") },
                )
            }
        }
    }
}

@Composable
private fun SessionHistoryCard(sessions: List<SessionSummary>) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.session_history),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))

            sessions.forEachIndexed { index, session ->
                SessionRow(session)
                if (index < sessions.size - 1) {
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: SessionSummary) {
    val dateFormat = remember { SimpleDateFormat("MMM dd HH:mm", Locale.US) }

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = dateFormat.format(Date(session.startTime)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = session.transport.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = stringResource(
                R.string.session_duration_format,
                formatDuration(session.durationSeconds),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                R.string.session_transferred_format,
                formatBytes(session.bytesTransferred),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (session.errorSummary.isNotEmpty()) {
            Text(
                text = session.errorSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < BYTES_PER_KB -> "$bytes B"
    bytes < BYTES_PER_MB -> "%.1f KB".format(bytes / BYTES_PER_KB.toDouble())
    bytes < BYTES_PER_GB -> "%.1f MB".format(bytes / BYTES_PER_MB.toDouble())
    else -> "%.2f GB".format(bytes / BYTES_PER_GB.toDouble())
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / SECONDS_PER_HOUR
    val minutes = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val secs = seconds % SECONDS_PER_MINUTE
    return when {
        hours > 0 -> "%dh %dm %ds".format(hours, minutes, secs)
        minutes > 0 -> "%dm %ds".format(minutes, secs)
        else -> "%ds".format(secs)
    }
}

private const val REFRESH_INTERVAL_MS = 2000L
private const val BYTES_PER_KB = 1024L
private const val BYTES_PER_MB = 1024L * 1024
private const val BYTES_PER_GB = 1024L * 1024 * 1024
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
