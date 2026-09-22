package io.github.nemanjan00.pm3.ui

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nemanjan00.pm3.MainViewModel
import io.github.nemanjan00.pm3.bridge.BridgeService

private enum class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Device("Device", Icons.Filled.Usb),
    Actions("Actions", Icons.Filled.Sensors),
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
            // consumeWindowInsets is the half that is easy to miss. Scaffold
            // hands down padding that already covers the bottom bar and the
            // navigation bar, but it does not mark those insets as consumed --
            // so a child calling imePadding() adds them a second time, and the
            // console's input row ends up displaced behind the bottom bar.
            // Consuming them makes the child's inset maths start from zero.
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
            ) {
                if (!serviceBound) {
                    LoadingPane()
                } else when (tab) {
                    Tab.Device -> DeviceScreen(viewModel, bridgeState)
                    Tab.Actions -> ActionsScreen(viewModel, bridgeState)
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
        is BridgeService.State.Lost ->
            Triple(
                state.deviceName,
                state.reason + " — reconnect on the Device tab",
                MaterialTheme.colorScheme.errorContainer,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConsoleScreen(viewModel: MainViewModel, state: BridgeService.State) {
    val lines by viewModel.console.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // Following the tail fights a user who has scrolled up to read something,
    // which is exactly when they are about to copy it. Only auto-scroll while
    // they are already at the bottom.
    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || last.index >= listState.layoutInfo.totalItemsCount - 2
        }
    }
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty() && atBottom) listState.animateScrollToItem(lines.lastIndex)
    }

    fun copy(text: String, what: String) {
        if (text.isEmpty()) return

        // The clipboard crosses a binder transaction, which is capped around
        // 1 MB for the whole transaction -- and a long session (an autopwn
        // run, a trace dump) can get there. Keep the tail, which is the part
        // someone wants, and say so rather than truncating silently.
        val truncated = text.length > CLIPBOARD_LIMIT
        val payload = if (truncated) {
            "[... earlier output omitted, clipboard limit reached ...]\n" +
                text.takeLast(CLIPBOARD_LIMIT)
        } else {
            text
        }

        clipboard.setText(AnnotatedString(payload))

        // Android 13+ shows its own copy confirmation, so a second one would
        // double up -- except when there is something extra to say.
        if (truncated) {
            Toast.makeText(context, "Copied the last ${CLIPBOARD_LIMIT / 1024} KB", Toast.LENGTH_LONG).show()
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, "Copied $what", Toast.LENGTH_SHORT).show()
        }
    }

    // imePadding: without it the keyboard covers the very input field it was
    // opened for, which on a console is the whole interaction.
    Column(Modifier.fillMaxSize().imePadding()) {

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${lines.size} lines",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
            )
            TextButton(
                onClick = { copy(lines.joinToString("\n"), "console output") },
                enabled = lines.isNotEmpty(),
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Copy all")
            }
            TextButton(onClick = { viewModel.clearConsole() }, enabled = lines.isNotEmpty()) {
                Text("Clear")
            }
        }

        // SelectionContainer gives the ordinary Android drag-to-select gesture
        // across the log, for pulling out a single UID rather than the lot.
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp),
            ) {
                items(lines) { line ->
                    Text(
                        line,
                        // Long-press copies just this line. Selection handles
                        // are fiddly on a 12sp monospace log, so the common
                        // case gets a gesture of its own.
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { copy(line, "line") },
                        ),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = lineColour(line),
                    )
                }
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

/** Well under the ~1 MB binder transaction limit, with room for overhead. */
private const val CLIPBOARD_LIMIT = 256 * 1024

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
