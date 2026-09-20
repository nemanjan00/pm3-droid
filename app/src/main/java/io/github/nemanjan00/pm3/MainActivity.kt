package io.github.nemanjan00.pm3

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.refreshDevices() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        BridgeService.start(this)
        bindService(
            Intent(this, BridgeService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
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
        runCatching { unbindService(connection) }
        super.onDestroy()
    }
}
