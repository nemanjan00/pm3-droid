package io.github.nemanjan00.pm3.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.nemanjan00.pm3.MainViewModel
import io.github.nemanjan00.pm3.bridge.BridgeService
import io.github.nemanjan00.pm3.termux.TermuxIntegration
import io.github.nemanjan00.pm3.transport.BtSppTransport
import io.github.nemanjan00.pm3.transport.UsbTransport

@Composable
fun DeviceScreen(viewModel: MainViewModel, state: BridgeService.State) {
    val context = LocalContext.current
    val devices by viewModel.devices.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    val termux = remember { TermuxIntegration(context) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state is BridgeService.State.Running) {
            ConnectedCard(viewModel, state, termux)
        }

        SectionCard(
            title = "USB",
            subtitle = "USB-OTG. The only link that can flash firmware.",
        ) {
            if (devices.usb.isEmpty()) {
                EmptyRow("No Proxmark on USB. Check the OTG cable — some phones " +
                    "cannot supply enough current without a powered hub.")
            }
            devices.usb.forEach { device ->
                DeviceRow(
                    icon = Icons.Filled.Usb,
                    title = device.productName ?: "Proxmark",
                    subtitle = "%04x:%04x".format(device.vendorId, device.productId),
                    onClick = {
                        if (hasUsbPermission(context, device)) {
                            viewModel.connectUsb(device)
                        } else {
                            UsbTransport.requestPermission(
                                context, device,
                                context.getString(io.github.nemanjan00.pm3.R.string.usb_permission),
                            )
                        }
                    },
                )
            }
        }

        SectionCard(
            title = "Bluetooth classic",
            subtitle = "Blueshark on a PM3. Pair in Android settings first. " +
                "Needs firmware built with PLATFORM_EXTRAS=BTADDON.",
        ) {
            if (devices.bonded.isEmpty()) {
                EmptyRow("No paired devices.")
            }
            devices.bonded.forEach { device ->
                DeviceRow(
                    icon = Icons.Filled.Bluetooth,
                    title = device.name ?: device.address,
                    subtitle = if (BtSppTransport.looksLikeProxmark(device))
                        "${device.address} · looks like a Proxmark"
                    else device.address,
                    onClick = { viewModel.connectBtClassic(device) },
                )
            }
        }

        SectionCard(
            title = "Bluetooth LE",
            subtitle = "Proxmark5 BWM. No pairing needed. " +
                "Needs firmware built with PLATFORM_EXTRAS=BWM.",
        ) {
            if (devices.scanned.isEmpty() && !scanning) {
                EmptyRow("Scan to find a Proxmark5 advertising the BWM SPP service.")
            }
            devices.scanned.forEach { device ->
                DeviceRow(
                    icon = Icons.AutoMirrored.Filled.BluetoothSearching,
                    title = device.name ?: device.address,
                    subtitle = device.address,
                    onClick = { viewModel.connectBle(device) },
                )
            }

            if (scanning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Scanning…", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { viewModel.stopScan() }) { Text("Stop") }
                }
            } else {
                OutlinedButton(
                    onClick = { viewModel.scanBle() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.BluetoothSearching, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Scan for BWM")
                }
            }
        }

        OutlinedButton(
            onClick = { viewModel.refreshDevices() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Refresh")
        }
    }
}

@Composable
private fun ConnectedCard(
    viewModel: MainViewModel,
    state: BridgeService.State.Running,
    termux: TermuxIntegration,
) {
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.deviceName, style = MaterialTheme.typography.titleMedium)
            Text(
                "tcp:127.0.0.1:${state.port}",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.startSession() }) { Text("Start client") }
                OutlinedButton(onClick = { viewModel.disconnect() }) { Text("Disconnect") }
            }

            HorizontalDivider()

            if (termux.isTermuxInstalled) {
                Text("Termux", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Installs a `pm3` wrapper into Termux that connects through " +
                        "this bridge. Run `pm3` in any session afterwards.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { termux.installWrapper(state.port) }) {
                        Text("Install pm3 wrapper")
                    }
                    OutlinedButton(onClick = { termux.openSession(state.port) }) {
                        Text("Open in Termux")
                    }
                }
            } else {
                Text(
                    "Termux is not installed. The app works on its own — the " +
                        "Console tab runs the bundled client.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}

@Composable
private fun DeviceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            TextButton(onClick = onClick) { Text("Connect") }
        },
    )
}

@Composable
private fun EmptyRow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun hasUsbPermission(context: Context, device: android.hardware.usb.UsbDevice): Boolean {
    val manager = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
    return manager.hasPermission(device)
}
