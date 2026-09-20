package io.github.nemanjan00.pm3.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.nemanjan00.pm3.MainViewModel
import io.github.nemanjan00.pm3.bridge.BridgeService
import io.github.nemanjan00.pm3.flash.Flasher
import java.io.File

@Composable
fun FlashScreen(viewModel: MainViewModel, state: BridgeService.State) {
    val progress by viewModel.flashProgress.collectAsState()
    var selected by remember { mutableStateOf<FirmwareVariant?>(null) }
    var confirming by remember { mutableStateOf(false) }

    val variants = remember { FirmwareCatalog.bundled() }
    val running = state as? BridgeService.State.Running
    val canFlash = running?.flashable == true

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (running == null) {
            Banner(
                "Connect a device on the Device tab first.",
                MaterialTheme.colorScheme.surfaceVariant,
            )
        } else if (!canFlash) {
            // The single most destructive mistake available in this app.
            Banner(
                "Flashing needs USB. Over Bluetooth the link drops the moment " +
                    "the device reboots into its bootloader, leaving the " +
                    "firmware half-written. Reconnect over USB-OTG to flash.",
                MaterialTheme.colorScheme.errorContainer,
            )
        }

        Text("Firmware", style = MaterialTheme.typography.titleMedium)
        Text(
            "Built from the matrix in firmware/matrix.conf. Pick the one that " +
                "matches your hardware — and, if you use a wireless module, the " +
                "variant that enables it.",
            style = MaterialTheme.typography.bodySmall,
        )

        variants.forEach { variant ->
            Card(
                colors = if (selected == variant)
                    CardDefaults.cardColors(MaterialTheme.colorScheme.secondaryContainer)
                else CardDefaults.cardColors(),
            ) {
                ListItem(
                    headlineContent = { Text(variant.id, fontFamily = FontFamily.Monospace) },
                    supportingContent = {
                        Column {
                            Text(variant.description)
                            Text(
                                variant.platform +
                                    if (variant.extras.isNotEmpty()) " · ${variant.extras}" else "",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                    trailingContent = {
                        RadioButton(
                            selected = selected == variant,
                            onClick = { selected = variant },
                        )
                    },
                )
            }
        }

        Button(
            onClick = { confirming = true },
            enabled = canFlash && selected != null && progress !is Flasher.Progress.Step,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Flash ${selected?.id ?: "…"}") }

        when (val p = progress) {
            is Flasher.Progress.Step -> {
                Text(p.label, style = MaterialTheme.typography.bodyMedium)
                if (p.fraction != null) {
                    LinearProgressIndicator(
                        progress = { p.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            is Flasher.Progress.Finished -> Banner(
                p.message,
                if (p.success) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.errorContainer,
            )
            else -> Unit
        }
    }

    if (confirming && selected != null) {
        val variant = selected!!
        AlertDialog(
            onDismissRequest = { confirming = false },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text("Flash ${variant.id}?") },
            text = {
                Text(
                    "This overwrites the firmware on the connected Proxmark.\n\n" +
                        "${variant.description}\n\n" +
                        "Do not unplug the device until it finishes. If it is " +
                        "interrupted the device stays in bootloader mode and " +
                        "you can retry — the bootloader itself is not touched."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    viewModel.flash(File(variant.path))
                }) { Text("Flash") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun Banner(text: String, colour: androidx.compose.ui.graphics.Color) {
    Surface(color = colour, shape = MaterialTheme.shapes.medium) {
        Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
