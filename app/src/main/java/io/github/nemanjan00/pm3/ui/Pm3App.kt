package io.github.nemanjan00.pm3.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nemanjan00.pm3.MainViewModel
import io.github.nemanjan00.pm3.bridge.BridgeService

private enum class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Device("Device", Icons.Filled.Usb),
    Flash("Flash", Icons.Filled.Memory),
    Console("Console", Icons.Filled.Terminal),
}

@Composable
fun Pm3App(viewModel: MainViewModel, serviceBound: Boolean) {
    // Dark by default: this is a tool used in the field, often one-handed in
    // poor light, and the console is the centre of gravity.
    MaterialTheme(colorScheme = darkColorScheme()) {
        var tab by rememberSaveable { mutableStateOf(Tab.Device) }

        val bridgeState by (viewModel.bridgeState
            ?: remember { MutableStateFlowOf<BridgeService.State>(BridgeService.State.Idle) })
            .collectAsState()

        Scaffold(
            topBar = { BridgeStatusBar(bridgeState) },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            icon = { Icon(entry.icon, contentDescription = entry.label) },
                            label = { Text(entry.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                if (!serviceBound) {
                    LoadingPane()
                } else when (tab) {
                    Tab.Device -> DeviceScreen(viewModel, bridgeState)
                    Tab.Flash -> FlashScreen(viewModel, bridgeState)
                    Tab.Console -> ConsoleScreen(viewModel, bridgeState)
                }
            }
        }
    }
}

@Composable
private fun LoadingPane() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Starting bridge service…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BridgeStatusBar(state: BridgeService.State) {
    val (title, subtitle, colour) = when (state) {
        BridgeService.State.Idle ->
            Triple("Not connected", "Pick a device below", MaterialTheme.colorScheme.surfaceVariant)
        is BridgeService.State.Connecting ->
            Triple(
                "Connecting…",
                state.deviceName,
                MaterialTheme.colorScheme.secondaryContainer,
            )
        is BridgeService.State.Running ->
            Triple(
                state.deviceName,
                "Bridging on tcp:127.0.0.1:${state.port}" +
                    if (!state.flashable) " · wireless, no flashing" else "",
                MaterialTheme.colorScheme.primaryContainer,
            )
        is BridgeService.State.Failed ->
            Triple("Connection failed", state.message, MaterialTheme.colorScheme.errorContainer)
    }

    Surface(color = colour) {
        // The surface stays full-bleed so its colour continues behind the
        // status bar; only the text is inset. Padding the Surface itself
        // would leave a bare strip of window background up there.
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ConsoleScreen(viewModel: MainViewModel, state: BridgeService.State) {
    val lines by viewModel.console.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Follow the tail as output arrives, the way a terminal does.
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    // imePadding: without it the keyboard covers the very input field it was
    // opened for, which on a console is the whole interaction.
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp),
        ) {
            items(lines) { line ->
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = lineColour(line),
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("hw status") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (input.isNotBlank()) { viewModel.send(input.trim()); input = "" }
                }),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = { if (input.isNotBlank()) { viewModel.send(input.trim()); input = "" } },
                enabled = state is BridgeService.State.Running,
            ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
        }
    }
}

/** Colours the client's own [+]/[!]/[=] severity markers. */
@Composable
private fun lineColour(line: String) = when {
    line.startsWith("[!!") || line.startsWith("[!") -> MaterialTheme.colorScheme.error
    line.startsWith("[+]") -> MaterialTheme.colorScheme.primary
    line.startsWith("[=]") -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.onSurface
}

/** Small shim so the UI can render before the service has bound. */
private fun <T> MutableStateFlowOf(value: T) = kotlinx.coroutines.flow.MutableStateFlow(value)
