package io.github.nemanjan00.pm3.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * BLE link to a Proxmark5 Battery Wireless Module.
 *
 * The BWM (ESP32-C2) exposes a NimBLE SPP service: 16-bit UUID 0xAE86 with a
 * single 0xAE88 characteristic carrying both directions -- notifications for
 * device to host, writes for host to device. Both UUIDs are read straight out
 * of the BWM firmware (components/app_ble_spp/ble_spp_server.h).
 *
 * No pairing is needed; the module does not bond by default.
 *
 * The device firmware must be built with PLATFORM_EXTRAS=BWM for the Proxmark
 * to forward its serial traffic to the module. The matrix ships a pm5-bwm
 * variant for this.
 */
@SuppressLint("MissingPermission") // callers hold BLUETOOTH_CONNECT; see BridgeService
class BleSppTransport(
    private val context: Context,
    private val device: BluetoothDevice,
) : Transport {

    override val displayName: String
        get() = "${device.name ?: device.address} (BLE)"

    // The bootloader does not bring up the BWM radio.
    override val supportsFlashing = false

    private var gatt: BluetoothGatt? = null
    private var spp: BluetoothGattCharacteristic? = null

    /** Notification payloads, drained by [read]. */
    private val inbound = ArrayBlockingQueue<ByteArray>(INBOUND_QUEUE_DEPTH)

    /** Bytes left over from a notification that did not fit the caller's buffer. */
    private var residue: ByteArray = ByteArray(0)

    private var negotiatedMtu = DEFAULT_MTU
    @Volatile private var connected = false

    private val connectedLatch = CountDownLatch(1)
    private val servicesLatch = CountDownLatch(1)
    private val mtuLatch = CountDownLatch(1)
    private val subscribedLatch = CountDownLatch(1)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true
                connectedLatch.countDown()
                // Ask for the largest MTU the stack will grant before doing
                // anything else: at the 23-byte default every Proxmark frame
                // fragments and throughput collapses.
                g.requestMtu(PREFERRED_MTU)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                // Release anyone still waiting so open() fails fast.
                connectedLatch.countDown()
                servicesLatch.countDown()
                mtuLatch.countDown()
                subscribedLatch.countDown()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu
            mtuLatch.countDown()
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                spp = g.getService(SPP_SERVICE_UUID)?.getCharacteristic(SPP_CHAR_UUID)
            }
            servicesLatch.countDown()
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int,
        ) {
            if (descriptor.uuid == CCCD_UUID) subscribedLatch.countDown()
        }

        // API 33+ delivers the value as a parameter; below that it is read off
        // the characteristic, which the framework mutates in place.
        override fun onCharacteristicChanged(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray,
        ) = enqueue(characteristic, value)

        @Deprecated("Superseded by the value-carrying overload on API 33+")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                enqueue(characteristic, characteristic.value ?: ByteArray(0))
            }
        }
    }

    private fun enqueue(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid != SPP_CHAR_UUID || value.isEmpty()) return
        // Drop rather than block: this runs on the binder thread and stalling
        // it wedges the whole GATT stack.
        inbound.offer(value.copyOf())
    }

    override fun open() {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw TransportException("connectGatt returned null")

        await(connectedLatch, CONNECT_TIMEOUT_MS) { "Timed out connecting to ${device.address}" }
        if (!connected) throw TransportException("Disconnected during connect")

        await(mtuLatch, GATT_TIMEOUT_MS) { "Timed out negotiating MTU" }
        await(servicesLatch, GATT_TIMEOUT_MS) { "Timed out discovering services" }

        val characteristic = spp ?: throw TransportException(
            "Device does not expose the BWM SPP service (${SPP_SERVICE_UUID}). " +
                "Is this a Proxmark5 BWM?"
        )

        val g = gatt ?: throw TransportException("GATT closed")
        if (!g.setCharacteristicNotification(characteristic, true)) {
            throw TransportException("Could not enable notifications")
        }
        // setCharacteristicNotification only arms the local stack; the remote
        // only starts sending once its CCCD is written.
        val cccd = characteristic.getDescriptor(CCCD_UUID)
            ?: throw TransportException("SPP characteristic has no CCCD")
        writeDescriptor(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        await(subscribedLatch, GATT_TIMEOUT_MS) { "Timed out subscribing to notifications" }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value)
        } else {
            d.value = value
            g.writeDescriptor(d)
        }
    }

    private inline fun await(latch: CountDownLatch, timeoutMs: Long, message: () -> String) {
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            close()
            throw TransportException(message())
        }
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        if (residue.isEmpty()) {
            val next = try {
                inbound.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return 0
            } ?: return 0
            residue = next
        }
        val n = minOf(buffer.size, residue.size)
        residue.copyInto(buffer, 0, 0, n)
        residue = if (n == residue.size) ByteArray(0) else residue.copyOfRange(n, residue.size)
        return n
    }

    @Suppress("DEPRECATION")
    override fun write(data: ByteArray) {
        val g = gatt ?: throw TransportException("Not connected")
        val characteristic = spp ?: throw TransportException("Not connected")

        // 3 bytes of ATT header come off the MTU for a write payload.
        val chunk = negotiatedMtu - ATT_HEADER_BYTES
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + chunk, data.size)
            val slice = data.copyOfRange(offset, end)
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(
                    characteristic, slice,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                ) == BluetoothGatt.GATT_SUCCESS
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                characteristic.value = slice
                g.writeCharacteristic(characteristic)
            }
            if (!ok) throw TransportException("BLE write rejected")
            offset = end
        }
    }

    override fun isConnected(): Boolean = connected

    override fun close() {
        connected = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        spp = null
        inbound.clear()
        residue = ByteArray(0)
    }

    companion object {
        /** BWM SPP service, 16-bit 0xAE86 expanded into the Bluetooth base UUID. */
        val SPP_SERVICE_UUID: UUID = uuid16(0xAE86)

        /** Single bidirectional SPP characteristic, 16-bit 0xAE88. */
        val SPP_CHAR_UUID: UUID = uuid16(0xAE88)

        /** Standard battery service the BWM also exposes (0x180F / 0x2A19). */
        val BATTERY_SERVICE_UUID: UUID = uuid16(0x180F)
        val BATTERY_LEVEL_UUID: UUID = uuid16(0x2A19)

        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private fun uuid16(short: Int): UUID =
            UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", short))

        private const val DEFAULT_MTU = 23
        private const val PREFERRED_MTU = 517
        private const val ATT_HEADER_BYTES = 3
        private const val INBOUND_QUEUE_DEPTH = 512
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val GATT_TIMEOUT_MS = 10_000L
    }
}
