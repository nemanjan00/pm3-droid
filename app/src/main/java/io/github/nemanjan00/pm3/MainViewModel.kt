package io.github.nemanjan00.pm3

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.hardware.usb.UsbDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.nemanjan00.pm3.actions.Pm3Action
import io.github.nemanjan00.pm3.actions.TagParser
import io.github.nemanjan00.pm3.bridge.BridgeService
import io.github.nemanjan00.pm3.bridge.TcpBridge
import io.github.nemanjan00.pm3.client.Pm3Runtime
import io.github.nemanjan00.pm3.client.Pm3Session
import io.github.nemanjan00.pm3.flash.Flasher
import io.github.nemanjan00.pm3.flash.FirmwareRepository
import io.github.nemanjan00.pm3.ui.FirmwareCatalog
import io.github.nemanjan00.pm3.ui.FirmwareVariant
import io.github.nemanjan00.pm3.transport.BleScanner
import io.github.nemanjan00.pm3.transport.BleSppTransport
import io.github.nemanjan00.pm3.transport.BtSppTransport
import io.github.nemanjan00.pm3.transport.UsbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val runtime: Pm3Runtime = (app as Pm3Application).runtime

    /** Devices offered in the picker, refreshed by [refreshDevices]. */
    data class Devices(
        val usb: List<UsbDevice> = emptyList(),
        val bonded: List<BluetoothDevice> = emptyList(),
        val scanned: List<BluetoothDevice> = emptyList(),
    )

    private val _devices = MutableStateFlow(Devices())
    val devices: StateFlow<Devices> = _devices

    private val _console = MutableStateFlow<List<String>>(emptyList())
    val console: StateFlow<List<String>> = _console

    private val _flashProgress = MutableStateFlow<Flasher.Progress?>(null)
    val flashProgress: StateFlow<Flasher.Progress?> = _flashProgress

    private val _firmware = MutableStateFlow(FirmwareCatalog.load(app))
    val firmware: StateFlow<List<FirmwareVariant>> = _firmware

    private val _firmwareSync = MutableStateFlow<FirmwareRepository.Progress?>(null)
    val firmwareSync: StateFlow<FirmwareRepository.Progress?> = _firmwareSync

    /** Result of the last action, shown as parsed fields plus raw output. */
    data class ActionResult(
        val action: Pm3Action,
        val raw: String,
        val lf: TagParser.LfTag? = null,
        val hf: TagParser.HfTag? = null,
        val antenna: TagParser.Antenna? = null,
        val failed: Boolean = false,
    )

    private val _runningAction = MutableStateFlow<Pm3Action?>(null)
    val runningAction: StateFlow<Pm3Action?> = _runningAction

    private val _actionResult = MutableStateFlow<ActionResult?>(null)
    val actionResult: StateFlow<ActionResult?> = _actionResult

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    /**
     * Stable key of the device being connected, set the instant the row is
     * tapped.
     *
     * The service's Connecting state arrives a beat later and only carries a
     * display name; this is what lets the tapped row show feedback right away
     * rather than looking like the tap was missed.
     */
    private val _connectingKey = MutableStateFlow<String?>(null)
    val connectingKey: StateFlow<String?> = _connectingKey

    private val bleScanner = BleScanner(app)

    private var service: BridgeService? = null
    private var session: Pm3Session? = null
    private val sessionLock = Mutex()

    fun attachService(s: BridgeService?) { service = s }

    val bridgeState: StateFlow<BridgeService.State>?
        get() = service?.state

    fun refreshDevices() {
        val context = getApplication<Application>()
        _devices.value = _devices.value.copy(
            usb = UsbTransport.list(context),
            bonded = BtSppTransport.bonded(context),
        )
    }

    /** Scans for a Proxmark5 BWM, which advertises the 0xAE86 SPP service. */
    fun scanBle() {
        if (_scanning.value) return
        _scanning.value = true
        bleScanner.start(
            onResult = { list -> _devices.value = _devices.value.copy(scanned = list) },
            onFinished = {
                _scanning.value = false
                if (_devices.value.scanned.isEmpty()) {
                    append("[=] No BWM found. Check the module is powered and the " +
                        "Proxmark5 runs firmware built with PLATFORM_EXTRAS=BWM.")
                }
            },
        )
    }

    fun stopScan() {
        bleScanner.stop()
        _scanning.value = false
    }

    fun connectUsb(device: UsbDevice) {
        _connectingKey.value = usbKey(device)
        service?.connect(UsbTransport(getApplication(), device))
    }

    fun connectBtClassic(device: BluetoothDevice) {
        _connectingKey.value = device.address
        service?.connect(BtSppTransport(getApplication(), device))
    }

    fun connectBle(device: BluetoothDevice) {
        _connectingKey.value = device.address
        service?.connect(BleSppTransport(getApplication(), device))
    }

    fun disconnect() {
        _connectingKey.value = null
        stopSession()
        service?.disconnect()
    }

    /** Called by the UI once the service leaves its Connecting state. */
    fun clearConnectingKey() {
        _connectingKey.value = null
    }

    /**
     * Returns a live session, starting one if needed.
     *
     * Serialised by [sessionLock] so two actions fired in quick succession
     * cannot each spawn a client -- two clients on one bridge would interleave
     * frames on a link that allows exactly one peer.
     */
    private suspend fun ensureSession(port: Int): Pm3Session? = sessionLock.withLock {
        session?.takeIf { it.isAlive }?.let { return@withLock it }

        runCatching { withContext(Dispatchers.IO) { runtime.install() } }
            .onFailure { append("[!] Could not unpack resources: ${it.message}"); return null }

        val s = Pm3Session(runtime, viewModelScope)
        viewModelScope.launch { s.output.collect { append(it) } }
        runCatching { s.start("tcp:127.0.0.1:$port") }
            .onFailure {
                append("[!] Client failed to start: ${it.message}")
                return null
            }
        session = s
        s
    }

    /** Starts the in-app client against the running bridge. */
    fun startSession() {
        val state = service?.state?.value
        if (state !is BridgeService.State.Running) {
            append("[!] Connect a device first")
            return
        }
        if (session?.isAlive == true) return

        viewModelScope.launch(Dispatchers.IO) {
            runCatching { runtime.install() }
                .onFailure { append("[!] Could not unpack resources: ${it.message}"); return@launch }

            val s = Pm3Session(runtime, viewModelScope)
            session = s
            viewModelScope.launch { s.output.collect { append(it) } }
            runCatching { s.start("tcp:127.0.0.1:${state.port}") }
                .onFailure { append("[!] Client failed to start: ${it.message}") }
        }
    }

    fun send(command: String) {
        val s = session
        if (s == null || !s.isAlive) {
            append("[!] No client session; press Connect first")
            return
        }
        viewModelScope.launch {
            runCatching { s.execute(command) }
                .onFailure { append("[!] ${it.message}") }
        }
    }

    fun stopSession() {
        session?.stop()
        session = null
    }

    /** Downloads the firmware manifest and any images not already cached. */
    fun syncFirmware() {
        if (_firmwareSync.value is FirmwareRepository.Progress.Downloading) return
        viewModelScope.launch {
            FirmwareRepository(getApplication()).sync().collect { progress ->
                _firmwareSync.value = progress
                when (progress) {
                    is FirmwareRepository.Progress.Done -> {
                        _firmware.value = progress.variants
                        append("[+] Firmware synced: ${progress.variants.count { it.isAvailable }} " +
                            "of ${progress.variants.size} images available")
                    }
                    is FirmwareRepository.Progress.Failed -> append("[!] ${progress.message}")
                    else -> Unit
                }
            }
        }
    }

    fun refreshFirmware() {
        _firmware.value = FirmwareCatalog.load(getApplication())
    }

    /**
     * Re-verifies an image against the manifest, then flashes it.
     *
     * The download already checked the digest, but the cache can rot in
     * between and nothing downstream would notice -- a truncated image still
     * parses as an ELF.
     */
    fun flashVerified(variant: FirmwareVariant, bootloader: Boolean = false) {
        val image = variant.image
        if (image == null) {
            append("[!] ${variant.id} is not downloaded")
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { FirmwareCatalog.verify(variant) }
            if (!ok) {
                append("[!] ${variant.id} no longer matches its manifest digest. " +
                    "Re-download it before flashing.")
                return@launch
            }
            flash(image, bootloader)
        }
    }

    fun flash(image: File, bootloader: Boolean = false) {
        val state = service?.state?.value
        if (state !is BridgeService.State.Running) {
            append("[!] Connect a device first")
            return
        }
        if (!state.flashable) {
            // Guarded in the UI too, but this is the one mistake that bricks
            // hardware, so it is refused at every layer.
            append("[!] Flashing requires USB. A wireless link drops when the " +
                "device reboots into its bootloader.")
            return
        }
        // The client owns the serial port during a flash; our own session must
        // let go of it first.
        stopSession()

        viewModelScope.launch {
            Flasher(runtime).flash("tcp:127.0.0.1:${state.port}", image, bootloader)
                .collect { progress ->
                    _flashProgress.value = progress
                    if (progress is Flasher.Progress.Line) append(progress.text)
                    if (progress is Flasher.Progress.Finished) append("[=] ${progress.message}")
                }
        }
    }

    /**
     * Runs [action] and parses its output.
     *
     * Starts the client session if it is not already up, so the Actions tab
     * works without a detour through the Console tab first.
     */
    fun run(action: Pm3Action) {
        if (_runningAction.value != null) return // one command at a time

        val state = service?.state?.value
        if (state !is BridgeService.State.Running) {
            append("[!] Connect a device first")
            return
        }

        _runningAction.value = action
        viewModelScope.launch {
            try {
                val s = ensureSession(state.port)
                if (s == null) {
                    _actionResult.value = ActionResult(
                        action, "Could not start the Proxmark client.", failed = true,
                    )
                    return@launch
                }
                val result = s.execute(action.command)
                _actionResult.value = parse(action, result.output, !result.ok)
            } catch (e: Exception) {
                _actionResult.value =
                    ActionResult(action, e.message ?: "Command failed", failed = true)
            } finally {
                _runningAction.value = null
            }
        }
    }

    private fun parse(action: Pm3Action, output: String, failed: Boolean): ActionResult {
        // Parse by what the command actually returns, not by group: `auto`
        // and `hf search` both emit 14a fields, and `auto` emits LF ones too.
        val lf = TagParser.lfSearch(output).takeIf { !it.isEmpty }
        val hf = TagParser.hf14aInfo(output).takeIf { !it.isEmpty }
        val antenna = if (action.id == "hw_tune") TagParser.hwTune(output) else null
        return ActionResult(action, output, lf, hf, antenna, failed)
    }

    fun clearActionResult() {
        _actionResult.value = null
    }

    private fun append(line: String) {
        _console.value = (_console.value + line).takeLast(CONSOLE_LINES)
    }

    override fun onCleared() {
        bleScanner.stop()
        stopSession()
        super.onCleared()
    }

    companion object {
        /** UsbDevice has no stable id across refreshes; its path does. */
        fun usbKey(device: UsbDevice): String = device.deviceName

        private const val CONSOLE_LINES = 2000
        const val DEFAULT_PORT = TcpBridge.DEFAULT_PORT
    }
}
