package io.github.nemanjan00.pm3.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nemanjan00.pm3.MainViewModel
import io.github.nemanjan00.pm3.actions.Pm3Action
import io.github.nemanjan00.pm3.bridge.BridgeService

@Composable
fun ActionsScreen(viewModel: MainViewModel, state: BridgeService.State) {
    val running by viewModel.runningAction.collectAsState()
    val result by viewModel.actionResult.collectAsState()
    val connected = state is BridgeService.State.Running
    var confirming by remember { mutableStateOf<Pm3Action?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!connected) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    "Connect a device on the Device tab to run commands.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        result?.let { ResultCard(it) { viewModel.clearActionResult() } }

        Pm3Action.byGroup().forEach { (group, actions) ->
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(group.title, style = MaterialTheme.typography.titleMedium)
                    Text(group.subtitle, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))

                    actions.forEach { action ->
                        ActionRow(
                            action = action,
                            // One command at a time: the client is a single
                            // REPL behind a single-peer link.
                            enabled = connected && running == null,
                            running = running?.id == action.id,
                            onRun = {
                                if (action.destructive) confirming = action
                                else viewModel.run(action)
                            },
                        )
                    }
                }
            }
        }
    }

    confirming?.let { action ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text(action.label) },
            text = {
                Text(
                    "${action.description}\n\nThis writes to the tag or the " +
                        "device. Run it against the wrong card and the change " +
                        "may not be reversible."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.run(action)
                    confirming = null
                }) { Text("Run") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ActionRow(
    action: Pm3Action,
    enabled: Boolean,
    running: Boolean,
    onRun: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(action.label) },
        supportingContent = {
            Column {
                Text(action.description, style = MaterialTheme.typography.bodySmall)
                Text(
                    action.command + if (action.slow) "  ·  can take a while" else "",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        trailingContent = {
            if (running) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                FilledIconButton(onClick = onRun, enabled = enabled) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Run ${action.label}")
                }
            }
        },
    )
}

@Composable
private fun ResultCard(result: MainViewModel.ActionResult, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var showRaw by remember(result) { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            if (result.failed) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    result.action.label,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }

            result.lf?.let { tag ->
                Field("Type", tag.type)
                Field("Chipset", tag.chipset)
                Field("ID", tag.id, mono = true)
                tag.extra.forEach { (k, v) -> Field(k, v) }
            }
            result.hf?.let { tag ->
                Field("UID", tag.uid, mono = true)
                Field("ATQA", tag.atqa, mono = true)
                Field("SAK", tag.sak, mono = true)
                Field("Type", tag.type)
            }
            result.t55xx?.let { t ->
                if (t.passwordSet) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            "This card has a password set. Writing to it " +
                                "without the password will fail, and on some " +
                                "T55xx a blind write can lock the card for good.",
                            Modifier.padding(8.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                // Rendered in the order the client printed them: the layout
                // groups related settings and is easier to follow than any
                // re-ordering of ours.
                t.fields.forEach { (label, value) ->
                    Field(label, value, mono = label == "Block0" || label.contains("Raw"))
                }
            }

            result.antenna?.let { a ->
                Field("LF voltage", a.lfVoltage)
                Field("LF optimal divisor", a.lfOptimalDivisor)
                Field("LF verdict", a.lfVerdict)
                Field("HF voltage", a.hfVoltage)
                Field("HF verdict", a.hfVerdict)
            }

            // Parsing is best-effort scraping of human-readable output, so the
            // raw text is always one tap away -- a field this missed is still
            // in there, and a wrong parse is visible rather than silent.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showRaw = !showRaw }) {
                    Text(if (showRaw) "Hide output" else "Show output")
                }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(result.raw))
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Copy")
                }
            }

            if (showRaw) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        result.raw.ifBlank { "(no output)" },
                        Modifier.fillMaxWidth().padding(8.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

/** Renders nothing when the value is absent, rather than an empty row. */
@Composable
private fun Field(label: String, value: String?, mono: Boolean = false) {
    if (value.isNullOrBlank()) return
    Row {
        Text(
            "$label ",
            Modifier.width(140.dp),
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else null,
        )
    }
}
