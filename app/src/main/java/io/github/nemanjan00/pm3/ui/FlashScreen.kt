package io.github.nemanjan00.pm3.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.nemanjan00.pm3.MainViewModel
import io.github.nemanjan00.pm3.bridge.BridgeService
import io.github.nemanjan00.pm3.flash.FirmwareRepository
import io.github.nemanjan00.pm3.flash.FlashProgressParser
import io.github.nemanjan00.pm3.flash.Flasher

@Composable
fun FlashScreen(viewModel: MainViewModel, state: BridgeService.State) {
    val progress by viewModel.flashProgress.collectAsState()
    var selected by remember { mutableStateOf<FirmwareVariant?>(null) }
    var confirming by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val variants by viewModel.firmware.collectAsState()
    val sync by viewModel.firmwareSync.collectAsState()
    val running = state as? BridgeService.State.Running
    val canFlash = running?.flashable == true

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Preconditions first, as a checklist. Flashing has two of them and
        // both are invisible until they fail, which is how people end up
        // staring at a device stuck in bootloader wondering what they did.
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Before you flash", style = MaterialTheme.typography.titleMedium)
                Check(
                    ok = running != null,
                    text = if (running != null) "Device connected — ${running.deviceName}"
                    else "Connect a device on the Device tab",
                )
                Check(
                    ok = canFlash,
                    text = if (canFlash) "Connected over USB"
                    else "Connect over USB-OTG. The bootloader refuses to flash " +
                        "over Bluetooth or the BWM, and the link would drop when " +
                        "the device reboots anyway.",
                )
                Check(
                    ok = variants.any { it.isAvailable },
                    text = if (variants.any { it.isAvailable }) "Firmware downloaded"
                    else "Download firmware below",
                )
                if (canFlash) {
                    Text(
                        "The Proxmark reboots into its bootloader and " +
                            "re-appears as a new USB device, so Android may ask " +
                            "for permission again — accept it. Leave it plugged " +
                            "in until it finishes.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Text("Firmware", style = MaterialTheme.typography.titleMedium)
        Text(
            "Built from the matrix in firmware/matrix.conf. Pick the one that " +
                "matches your hardware — and, if you use a wireless module, the " +
                "variant that enables it.",
            style = MaterialTheme.typography.bodySmall,
        )

        if (variants.isEmpty()) {
            Banner(
                "No firmware downloaded yet.\n\n" +
                    "Images are published by the firmware matrix build rather " +
                    "than bundled in the app, so they can be updated without " +
                    "waiting for an app release.",
                MaterialTheme.colorScheme.surfaceVariant,
            )
        }

        when (val s = sync) {
            is FirmwareRepository.Progress.Status ->
                Text(s.message, style = MaterialTheme.typography.bodySmall)
            is FirmwareRepository.Progress.Downloading -> {
                Text("Downloading ${s.name}…", style = MaterialTheme.typography.bodySmall)
                if (s.fraction != null) {
                    LinearProgressIndicator({ s.fraction }, Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            is FirmwareRepository.Progress.Failed ->
                Banner(s.message, MaterialTheme.colorScheme.errorContainer)
            else -> Unit
        }

        OutlinedButton(
            onClick = { viewModel.syncFirmware() },
            enabled = sync !is FirmwareRepository.Progress.Downloading,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (variants.isEmpty()) "Download firmware" else "Check for updates") }

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
                                    (if (variant.extras.isNotEmpty()) " · ${variant.extras}" else "") +
                                    (if (variant.isAvailable) "" else " · not downloaded"),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                    trailingContent = {
                        RadioButton(
                            selected = selected == variant,
                            enabled = variant.isAvailable,
                            onClick = { selected = variant },
                        )
                    },
                )
            }
        }

        Button(
            onClick = { confirming = true },
            enabled = canFlash && selected?.isAvailable == true &&
                progress !is Flasher.Progress.Step,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Flash ${selected?.id ?: "…"}") }

        when (val p = progress) {
            is Flasher.Progress.Step -> {
                Card {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // The whole sequence, with the current step marked, so
                        // a long silent stage does not read as a hang.
                        FlashProgressParser.Stage.entries.forEach { stage ->
                            val reached = p.stage != null && stage.ordinal <= p.stage.ordinal
                            val current = stage == p.stage
                            Text(
                                (if (current) "▸ " else if (reached) "✓ " else "   ") + stage.label,
                                style = if (current) MaterialTheme.typography.bodyMedium
                                else MaterialTheme.typography.bodySmall,
                                color = if (reached) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (p.fraction != null) {
                            LinearProgressIndicator(
                                progress = { p.fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        Text(
                            "Do not unplug the device.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            is Flasher.Progress.Finished -> {
                Card(
                    colors = CardDefaults.cardColors(
                        if (p.success) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(p.message, style = MaterialTheme.typography.titleSmall)
                        p.failure?.let { f ->
                            Text(f.whatToDo, style = MaterialTheme.typography.bodyMedium)
                            if (f.deviceInBootloader) {
                                Text(
                                    "The device is in bootloader mode, not " +
                                        "broken. Its old firmware is intact.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (f.retryable && canFlash) {
                                Button(onClick = { selected?.let { confirming = true } }) {
                                    Text("Try again")
                                }
                            }
                        }
                    }
                }
            }
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
                    viewModel.flashVerified(variant)
                }) { Text("Flash") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}

/** A precondition, shown met or unmet rather than only complained about. */
@Composable
private fun Check(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            if (ok) "✓" else "•",
            Modifier.width(24.dp),
            color = if (ok) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
        )
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Banner(text: String, colour: androidx.compose.ui.graphics.Color) {
    Surface(color = colour, shape = MaterialTheme.shapes.medium) {
        Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
