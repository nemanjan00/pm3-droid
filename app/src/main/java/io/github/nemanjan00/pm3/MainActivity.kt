package io.github.nemanjan00.pm3

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import io.github.nemanjan00.pm3.bridge.BridgeService
import io.github.nemanjan00.pm3.ui.Pm3App

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var bound by mutableStateOf(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? BridgeService.LocalBinder)?.service
            viewModel.attachService(service)
            bound = service != null
            viewModel.refreshDevices()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            viewModel.attachService(null)
            bound = false
        }
    }

    /**
     * Result of the system's USB permission prompt.
     *
     * UsbManager.requestPermission answers by broadcast, not by activity
     * result, so this is the only way to know the user said yes -- and it is
     * where the connection is actually made.
     */
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != usbPermissionAction()) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val device: UsbDevice? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
            if (granted && device != null) viewModel.connectUsb(device)
            viewModel.refreshDevices()
        }
    }

    private fun usbPermissionAction() = getString(R.string.usb_permission)

    /**
     * Bluetooth being switched on or off, from anywhere -- our own prompt, the
     * quick settings tile, or Settings. Without this the picker keeps showing
     * a stale adapter state until something else happens to refresh it.
     */
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                viewModel.refreshDevices()
            }
        }
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.refreshDevices() }

    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 35 draws edge to edge on Android 15 regardless; opting in
        // explicitly makes the behaviour the same on older releases, so the
        // inset padding in the UI is not version-dependent.
        //
        // Forced dark, because the UI is unconditionally darkColorScheme.
        // The default (auto) follows the *system* theme, which on a
        // light-themed phone would paint dark status icons onto our dark bar.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        BridgeService.start(this)
        bindService(
            Intent(this, BridgeService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )

        // RECEIVER_NOT_EXPORTED: only the system's own permission response
        // should reach this, never another app.
        ContextCompat.registerReceiver(
            this,
            usbPermissionReceiver,
            IntentFilter(usbPermissionAction()),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        ContextCompat.registerReceiver(
            this,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )

        requestPermissions.launch(requiredPermissions())

        setContent {
            Pm3App(viewModel = viewModel, serviceBound = bound)
        }
    }

    /**
     * Bluetooth permissions split at API 31; notifications became runtime at
     * API 33. Below 31 a BLE scan still requires location.
     */
    private fun requiredPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    override fun onResume() {
        super.onResume()
        // A device may have been plugged in while we were backgrounded.
        viewModel.refreshDevices()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbPermissionReceiver) }
        runCatching { unregisterReceiver(bluetoothStateReceiver) }
        runCatching { unbindService(connection) }
        super.onDestroy()
    }
}
