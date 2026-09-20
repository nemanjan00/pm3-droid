package io.github.nemanjan00.pm3

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.hardware.usb.UsbDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val bleScanner = BleScanner(app)

    private var service: BridgeService? = null
    private var session: Pm3Session? = null

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
        service?.connect(UsbTransport(getApplication(), device))
    }

    fun connectBtClassic(device: BluetoothDevice) {
        service?.connect(BtSppTransport(getApplication(), device))
    }

    fun connectBle(device: BluetoothDevice) {
        service?.connect(BleSppTransport(getApplication(), device))
    }

    fun disconnect() {
        stopSession()
        service?.disconnect()
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

    private fun append(line: String) {
        _console.value = (_console.value + line).takeLast(CONSOLE_LINES)
    }

    override fun onCleared() {
        bleScanner.stop()
        stopSession()
        super.onCleared()
    }

    companion object {
        private const val CONSOLE_LINES = 2000
        const val DEFAULT_PORT = TcpBridge.DEFAULT_PORT
    }
}
