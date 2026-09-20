package io.github.nemanjan00.pm3.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth-classic RFCOMM link to a Blueshark add-on on a PM3.
 *
 * The Blueshark is an HC-05 style SPP module, so this is a plain RFCOMM socket
 * to an already-paired device -- pairing happens in Android settings, not here.
 *
 * The device firmware must be built with PLATFORM_EXTRAS=BTADDON. Without it
 * the module powers up and pairs, the blue LED goes solid, and the client then
 * times out with "cannot communicate with the Proxmark3" -- the single most
 * common wireless failure. The firmware matrix ships -bt variants for this.
 */
@SuppressLint("MissingPermission") // callers hold BLUETOOTH_CONNECT; see BridgeService
class BtSppTransport(
    private val context: Context,
    private val device: BluetoothDevice,
) : Transport {

    override val displayName: String
        get() = "${device.name ?: device.address} (BT)"

    // The bootloader does not bring up the Blueshark radio, so a flash over BT
    // would drop mid-write and brick the device.
    override val supportsFlashing = false

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override fun open() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: throw TransportException("No Bluetooth adapter")

        val s = try {
            device.createRfcommSocketToServiceRecord(SPP_UUID)
        } catch (e: Exception) {
            throw TransportException("Could not create RFCOMM socket", e)
        }

        // Discovery starves the connect attempt and is a classic source of
        // spurious pairing failures.
        runCatching { adapter.cancelDiscovery() }

        try {
            s.connect()
        } catch (e: Exception) {
            runCatching { s.close() }
            throw TransportException("Could not connect to ${device.address}", e)
        }

        socket = s
        input = s.inputStream
        output = s.outputStream
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val stream = input ?: throw TransportException("Not connected")
        return try {
            // BluetoothSocket streams have no read timeout, but available()
            // lets us stay non-blocking; the bridge's pump loop supplies the
            // pacing. A blocking read here would wedge the pump on a quiet link.
            if (stream.available() == 0) return 0
            stream.read(buffer)
        } catch (e: Exception) {
            throw TransportException("BT read failed", e)
        }
    }

    override fun write(data: ByteArray) {
        val stream = output ?: throw TransportException("Not connected")
        try {
            stream.write(data)
            stream.flush()
        } catch (e: Exception) {
            throw TransportException("BT write failed", e)
        }
    }

    override fun isConnected(): Boolean = socket?.isConnected == true

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    companion object {
        /** Standard Serial Port Profile UUID. */
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /**
         * Paired devices that look like a Proxmark.
         *
         * The Blueshark advertises a user-settable name (factory default
         * "PM3_RDV4.0"), so this is a hint for ordering the picker, not a
         * filter -- the UI lists every paired device and marks the likely ones.
         */
        fun looksLikeProxmark(device: BluetoothDevice): Boolean {
            val n = device.name?.uppercase() ?: return false
            return n.contains("PM3") || n.contains("PROXMARK") || n.contains("RDV4")
        }

        fun bonded(context: Context): List<BluetoothDevice> {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
            return try {
                adapter.bondedDevices.orEmpty().toList()
                    .sortedByDescending { looksLikeProxmark(it) }
            } catch (_: SecurityException) {
                emptyList()
            }
        }
    }
}
