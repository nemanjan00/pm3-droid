package io.github.nemanjan00.pm3.transport

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable

/** USB-OTG link to a Proxmark, via CDC-ACM. */
class UsbTransport(
    private val context: Context,
    private val device: UsbDevice,
) : Transport {

    override val displayName: String
        get() = "${device.productName ?: "Proxmark"} (USB)"

    // Flashing reboots into the bootloader, which only ever comes back on USB.
    override val supportsFlashing = true

    override var onDisconnected: ((String) -> Unit)? = null

    @Volatile private var closing = false

    private var port: UsbSerialPort? = null

    override fun open() {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!manager.hasPermission(device)) {
            throw TransportException("No USB permission for ${device.deviceName}")
        }

        val driver: UsbSerialDriver = PROBER.probeDevice(device)
            ?: throw TransportException("No serial driver for ${device.deviceName}")

        val connection = manager.openDevice(device)
            ?: throw TransportException("Could not open ${device.deviceName}")

        val p = driver.ports.firstOrNull()
            ?: throw TransportException("Device exposes no serial port")

        try {
            p.open(connection)
            // The Proxmark's CDC-ACM endpoint ignores line coding -- it is USB
            // all the way down, not a real UART -- but the driver insists on a
            // configuration, and 115200 8N1 is what every other host uses.
            p.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // DTR high: the firmware's CDC stack gates transmission on it, and
            // without this the device accepts commands but never answers.
            p.dtr = true
            p.rts = true
        } catch (e: Exception) {
            runCatching { p.close() }
            throw TransportException("Failed to configure USB serial port", e)
        }
        port = p
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val p = port ?: throw TransportException("Port not open")
        return try {
            // usb-serial-for-android returns 0 on timeout rather than throwing.
            p.read(buffer, timeoutMs)
        } catch (e: Exception) {
            // Unplugging mid-session shows up as an IO error here.
            reportDropped()
            throw TransportException("USB read failed", e)
        }
    }

    override fun write(data: ByteArray) {
        val p = port ?: throw TransportException("Port not open")
        try {
            p.write(data, WRITE_TIMEOUT_MS)
        } catch (e: Exception) {
            reportDropped()
            throw TransportException("USB write failed", e)
        }
    }

    override fun isConnected(): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return manager.deviceList.values.any { it.deviceId == device.deviceId }
    }

    private fun reportDropped() {
        if (closing) return
        closing = true // report once
        onDisconnected?.invoke("${device.productName ?: "Proxmark"} was unplugged")
    }

    override fun close() {
        closing = true
        runCatching { port?.close() }
        port = null
    }

    companion object {
        private const val BAUD_RATE = 115200
        private const val WRITE_TIMEOUT_MS = 2000

        /** Proxmark USB IDs, from the project's own udev rules (driver/77-pm3-*.rules). */
        private val USB_IDS = listOf(
            0x2d2d to 0x504d, // Proxmark
            0x9ac4 to 0x4b8f, // RRG / Iceman
        )

        /**
         * The stock prober matches CDC-ACM by interface class, which the
         * Proxmark does advertise -- but some phones hand us the device before
         * the interface descriptors are readable. Pinning the known IDs to the
         * CDC-ACM driver makes the match deterministic.
         */
        private val PROBER: UsbSerialProber = run {
            val table = ProbeTable()
            USB_IDS.forEach { (vid, pid) ->
                table.addProduct(vid, pid, CdcAcmSerialDriver::class.java)
            }
            UsbSerialProber(table)
        }

        fun isProxmark(device: UsbDevice): Boolean =
            USB_IDS.any { (vid, pid) -> device.vendorId == vid && device.productId == pid }

        /** Proxmark devices currently attached. */
        fun list(context: Context): List<UsbDevice> {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            return manager.deviceList.values.filter { isProxmark(it) }
        }

        fun requestPermission(context: Context, device: UsbDevice, action: String) {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val intent = PendingIntent.getBroadcast(
                context, 0, Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            manager.requestPermission(device, intent)
        }
    }
}
