package org.flossware.hotspot.client.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.flossware.hotspot.client.R
import org.flossware.hotspot.client.model.VpnState
import org.flossware.hotspot.client.viewmodel.ClientViewModel

@Composable
fun ClientScreen(viewModel: ClientViewModel = viewModel()) {
    val state by viewModel.vpnState.collectAsState()
    var socksAddress by remember { mutableStateOf("${VpnState.DEFAULT_SOCKS_HOST}:${VpnState.DEFAULT_SOCKS_PORT}") }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val (host, port) = parseAddress(socksAddress)
            viewModel.connect(host, port)
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 32.dp),
            )

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = socksAddress,
                        onValueChange = { socksAddress = it },
                        label = { Text(stringResource(R.string.socks_server_label)) },
                        placeholder = { Text(stringResource(R.string.socks_server_hint)) },
                        singleLine = true,
                        enabled = !state.isConnected,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = if (state.isConnected) stringResource(R.string.connected)
                            else stringResource(R.string.disconnected),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (state.isConnected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    if (state.isConnected) {
                        viewModel.disconnect()
                    } else {
                        val vpnIntent = viewModel.prepareVpn()
                        if (vpnIntent != null) {
                            vpnLauncher.launch(vpnIntent)
                        } else {
                            val (host, port) = parseAddress(socksAddress)
                            viewModel.connect(host, port)
                        }
                    }
                },
                colors = if (state.isConnected) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text(
                    text = if (state.isConnected) stringResource(R.string.disconnect)
                        else stringResource(R.string.connect),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            state.error?.let { error ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun parseAddress(address: String): Pair<String, Int> {
    val parts = address.split(":")
    val host = parts.getOrElse(0) { VpnState.DEFAULT_SOCKS_HOST }
    val port = parts.getOrElse(1) { "${VpnState.DEFAULT_SOCKS_PORT}" }.toIntOrNull() ?: VpnState.DEFAULT_SOCKS_PORT
    return host to port
}
