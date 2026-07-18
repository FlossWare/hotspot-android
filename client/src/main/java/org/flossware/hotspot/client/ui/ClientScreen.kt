package org.flossware.hotspot.client.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import org.flossware.hotspot.client.R
import org.flossware.hotspot.client.model.VpnState
import org.flossware.hotspot.client.ui.components.ConnectionForm
import org.flossware.hotspot.client.ui.components.TransportSelector
import org.flossware.hotspot.client.viewmodel.BluetoothDeviceInfo
import org.flossware.hotspot.client.viewmodel.ClientViewModel

@Composable
fun ClientScreen(viewModel: ClientViewModel = viewModel()) {
    val state by viewModel.vpnState.collectAsState()
    var socksAddress by remember { mutableStateOf("${VpnState.DEFAULT_SOCKS_HOST}:${VpnState.DEFAULT_SOCKS_PORT}") }
    var selectedTransport by remember { mutableIntStateOf(0) }
    var selectedBtDevice by remember { mutableStateOf<BluetoothDeviceInfo?>(null) }
    var pairedDevices by remember { mutableStateOf<List<BluetoothDeviceInfo>>(emptyList()) }
    val context = LocalContext.current

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            if (selectedTransport == 0) {
                val (host, port) = parseAddress(socksAddress)
                viewModel.connect(host, port)
            } else {
                selectedBtDevice?.let { viewModel.connectBluetooth(it.address) }
            }
        }
    }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            pairedDevices = viewModel.getPairedDevices()
        }
    }

    fun hasBtPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    fun requestBtPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            btPermissionLauncher.launch(arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            ))
        }
    }

    fun refreshPairedDevices() {
        if (hasBtPermissions()) {
            pairedDevices = viewModel.getPairedDevices()
        } else {
            requestBtPermissions()
        }
    }

    fun onConnect() {
        val vpnIntent = viewModel.prepareVpn()
        if (vpnIntent != null) {
            vpnLauncher.launch(vpnIntent)
        } else {
            if (selectedTransport == 0) {
                val (host, port) = parseAddress(socksAddress)
                viewModel.connect(host, port)
            } else {
                selectedBtDevice?.let { viewModel.connectBluetooth(it.address) }
            }
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.flossware_logo),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier
                    .width(200.dp)
                    .height(56.dp)
                    .padding(bottom = 8.dp),
            )

            TransportSelector(
                selectedTransport = selectedTransport,
                onTransportSelected = { transport ->
                    selectedTransport = transport
                    if (transport == 1) {
                        refreshPairedDevices()
                    }
                },
                enabled = !state.isConnected,
            )

            ConnectionForm(
                selectedTransport = selectedTransport,
                socksAddress = socksAddress,
                onAddressChange = { socksAddress = it },
                isConnected = state.isConnected,
                connectedTransport = state.transport,
                pairedDevices = pairedDevices,
                selectedBtDevice = selectedBtDevice,
                onDeviceSelected = { selectedBtDevice = it },
                onRefreshDevices = { refreshPairedDevices() },
                onConnect = { onConnect() },
                onDisconnect = { viewModel.disconnect() },
                connectEnabled = state.isConnected || selectedTransport == 0 || selectedBtDevice != null,
            )

            state.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun parseAddress(address: String): Pair<String, Int> {
    val parts = address.split(":")
    val host = parts.getOrElse(0) { VpnState.DEFAULT_SOCKS_HOST }
    val port = parts.getOrElse(1) { "${VpnState.DEFAULT_SOCKS_PORT}" }.toIntOrNull() ?: VpnState.DEFAULT_SOCKS_PORT
    return host to port
}
