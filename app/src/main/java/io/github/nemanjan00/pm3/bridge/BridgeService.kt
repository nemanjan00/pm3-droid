package io.github.nemanjan00.pm3.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import io.github.nemanjan00.pm3.MainActivity
import io.github.nemanjan00.pm3.R
import io.github.nemanjan00.pm3.transport.Transport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
        data class Running(val deviceName: String, val port: Int, val flashable: Boolean) : State
        data class Failed(val message: String) : State
    }

    private val binder = LocalBinder()
    private var transport: Transport? = null
    private var bridge: TcpBridge? = null

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    inner class LocalBinder : Binder() {
        val service: BridgeService get() = this@BridgeService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.bridge_idle)))
        // Restarting with no transport would leave a service with nothing to
        // do; the UI re-establishes the link explicitly.
        return START_NOT_STICKY
    }

    /**
     * Opens [newTransport] and starts bridging it on [port].
     *
     * Any previous link is torn down first: the device speaks to one host at a
     * time, and leaving a stale transport open blocks the new one.
     */
    fun connect(newTransport: Transport, port: Int = TcpBridge.DEFAULT_PORT) {
        disconnect()
        try {
            newTransport.open()
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: "Could not open ${newTransport.displayName}")
            append("[!] ${e.message}")
            return
        }

        val b = TcpBridge(newTransport, port) { event -> onBridgeEvent(event) }
        try {
            b.start()
        } catch (e: Exception) {
            runCatching { newTransport.close() }
            _state.value = State.Failed("Could not bind port $port: ${e.message}")
            return
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

    fun disconnect() {
        bridge?.stop()
        runCatching { transport?.close() }
        bridge = null
        transport = null
        _state.value = State.Idle
        updateNotification(getString(R.string.bridge_idle))
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
        disconnect()
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
