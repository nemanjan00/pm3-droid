package io.github.nemanjan00.pm3.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import io.github.nemanjan00.pm3.MainActivity
import io.github.nemanjan00.pm3.R
import io.github.nemanjan00.pm3.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the transport and its TCP bridge alive while the app is backgrounded.
 *
 * A foreground service is not optional here: a Proxmark session routinely
 * outlives the UI -- the user switches to Termux to run the client, or leaves a
 * long `hf mf autopwn` running with the screen off. A background service would
 * be frozen or killed, dropping the link mid-command.
 */
class BridgeService : Service() {

    /** Bridge state, observed by the UI. */
    sealed interface State {
        data object Idle : State
        /** Opening the transport. Can take many seconds on BLE. */
        data class Connecting(val deviceName: String) : State
        data class Running(val deviceName: String, val port: Int, val flashable: Boolean) : State
        data class Failed(val message: String) : State

        /** The link was up and the far end dropped it. */
        data class Lost(val deviceName: String, val reason: String) : State
    }

    private val binder = LocalBinder()
    private var transport: Transport? = null
    private var bridge: TcpBridge? = null

    /**
     * Opening a transport blocks -- a BLE connect waits on GATT round trips,
     * an RFCOMM connect on the radio -- so it cannot run on the caller's
     * thread, which is the UI's.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The in-flight connect, so Cancel can actually abort it. */
    private var connectJob: Job? = null

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    inner class LocalBinder : Binder() {
        val service: BridgeService get() = this@BridgeService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * Switching Bluetooth off does not always surface as a socket or GATT
     * error in time, so watch the adapter directly -- otherwise the UI keeps
     * claiming a live link over a radio that is no longer on.
     */
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (state != BluetoothAdapter.STATE_TURNING_OFF && state != BluetoothAdapter.STATE_OFF) {
                return
            }
            val active = transport ?: return
            if (active.supportsFlashing) return // USB is unaffected
            onDeviceLost(active.displayName, "Bluetooth was turned off")
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.bridge_idle)))
        // Restarting with no transport would leave a service with nothing to
        // do; the UI re-establishes the link explicitly.
        return START_NOT_STICKY
    }

    /**
     * Opens [newTransport] and starts bridging it on [port].
     *
     * Returns immediately; watch [state] for the outcome. Any previous link is
     * torn down first -- the device speaks to one host at a time, and leaving a
     * stale transport open blocks the new one.
     *
     * A second call while one is already in flight is ignored rather than
     * queued. Opening a transport is slow enough to double-tap through, and
     * running two opens concurrently would have the second tear down the link
     * the first had just established.
     */
    @Synchronized
    fun connect(newTransport: Transport, port: Int = TcpBridge.DEFAULT_PORT) {
        if (_state.value is State.Connecting && connectJob?.isActive == true) {
            return
        }
        _state.value = State.Connecting(newTransport.displayName)
        updateNotification(getString(R.string.bridge_connecting, newTransport.displayName))

        connectJob = scope.launch {
            disconnectInternal(resetState = false)

            // Registered before open() so a drop during negotiation is caught.
            newTransport.onDisconnected = { reason ->
                onDeviceLost(newTransport.displayName, reason)
            }

            try {
                newTransport.open()
            } catch (e: Exception) {
                runCatching { newTransport.close() }
                _state.value =
                    State.Failed(e.message ?: "Could not open ${newTransport.displayName}")
                append("[!] ${e.message}")
                updateNotification(getString(R.string.bridge_idle))
                return@launch
            }

            // open() blocks and does not observe cancellation, so Cancel is
            // honoured here instead: drop the link we just made rather than
            // publishing Running over a user who asked us to stop.
            if (!isActive) {
                runCatching { newTransport.close() }
                return@launch
            }

            val b = TcpBridge(newTransport, port) { event -> onBridgeEvent(event) }
            try {
                b.start()
            } catch (e: Exception) {
                runCatching { newTransport.close() }
                _state.value = State.Failed("Could not bind port $port: ${e.message}")
                updateNotification(getString(R.string.bridge_idle))
                return@launch
            }

            transport = newTransport
            bridge = b
            _state.value = State.Running(
                deviceName = newTransport.displayName,
                port = b.boundPort,
                flashable = newTransport.supportsFlashing,
            )
            updateNotification(
                getString(R.string.bridge_running, newTransport.displayName, b.boundPort)
            )
        }
    }

    /**
     * The far end went away.
     *
     * Tears the link down and says so, rather than leaving the UI and the
     * notification claiming a device that is not there. Not a Failed state:
     * the distinction between "could not connect" and "was connected and lost
     * it" is the difference between checking the pairing and checking whether
     * the battery died.
     */
    @Synchronized
    private fun onDeviceLost(deviceName: String, reason: String) {
        if (transport == null && connectJob?.isActive != true) return
        append("[!] $reason")
        connectJob?.cancel()
        connectJob = null
        scope.launch {
            disconnectInternal(resetState = false)
            _state.value = State.Lost(deviceName, reason)
            updateNotification(reason)
        }
    }

    @Synchronized
    fun disconnect() {
        // Cancel first: an in-flight connect would otherwise finish and
        // publish Running over the state this is about to clear.
        connectJob?.cancel()
        connectJob = null
        scope.launch { disconnectInternal(resetState = true) }
    }

    /**
     * @param resetState false when a connect is about to publish its own
     *   state; clearing to Idle first would make the UI flicker back to the
     *   picker mid-connect.
     */
    private fun disconnectInternal(resetState: Boolean) {
        bridge?.stop()
        runCatching { transport?.close() }
        bridge = null
        transport = null
        if (resetState) {
            _state.value = State.Idle
            updateNotification(getString(R.string.bridge_idle))
        }
    }

    val activeTransport: Transport? get() = transport

    private fun onBridgeEvent(event: TcpBridge.Event) {
        when (event) {
            is TcpBridge.Event.Listening -> append("[=] Listening on 127.0.0.1:${event.port}")
            is TcpBridge.Event.ClientConnected -> append("[+] Client connected")
            TcpBridge.Event.ClientDisconnected -> append("[=] Client disconnected")
            is TcpBridge.Event.Error -> append("[!] ${event.message}: ${event.cause?.message ?: ""}")
            TcpBridge.Event.Stopped -> append("[=] Bridge stopped")
        }
    }

    private fun append(line: String) {
        _log.value = (_log.value + line).takeLast(LOG_LINES)
    }

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(CHANNEL_ID) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_bridge),
                    // Low: this is a persistent status, not an alert.
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_bridge)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(bluetoothStateReceiver) }
        connectJob?.cancel()
        disconnectInternal(resetState = true)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "pm3-bridge"
        private const val NOTIFICATION_ID = 1
        private const val LOG_LINES = 200

        /**
         * connectedDevice is the honest type: the service exists to hold a
         * USB/Bluetooth link open, and Android requires the type to match the
         * permissions declared in the manifest.
         */
        val FOREGROUND_TYPE: Int
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else 0

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BridgeService::class.java))
        }
    }
}
