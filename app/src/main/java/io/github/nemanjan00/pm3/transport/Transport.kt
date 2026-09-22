package io.github.nemanjan00.pm3.transport

import java.io.Closeable

/**
 * A byte-stream link to a Proxmark device.
 *
 * The Proxmark client speaks a framed binary protocol over what it believes is
 * a serial port. On Android it never touches the hardware itself: the client is
 * built with SKIPBT=1 and no USB access, and instead connects to a localhost
 * TCP socket that [io.github.nemanjan00.pm3.bridge.BridgeService] pumps into
 * one of these.
 */
interface Transport : Closeable {

    /** Human-readable label for the UI, e.g. "Proxmark3 (USB)". */
    val displayName: String

    /**
     * Invoked when the link drops from the far end -- the device powered off,
     * went out of range, was unplugged, or the adapter was switched off.
     *
     * Polling [isConnected] is not enough on its own: the bridge only reads
     * the transport while a TCP client is attached, so a device that vanishes
     * between commands would go unnoticed and the UI would keep claiming a
     * live link. This fires regardless.
     *
     * Never fired for a close this app initiated.
     */
    var onDisconnected: ((reason: String) -> Unit)?

    /**
     * Whether firmware can be flashed over this link.
     *
     * USB only. Flashing reboots the device into its bootloader, which does not
     * bring up the Blueshark/BWM radio, so a wireless link drops mid-flash and
     * leaves the device half-written. Upstream's termux notes are explicit that
     * flashing is "possible only via USB-UART, *not* via BT-UART".
     */
    val supportsFlashing: Boolean

    /** Opens the link. Throws [TransportException] if the device is unreachable. */
    fun open()

    /**
     * Reads up to [buffer].size bytes, blocking until at least one is available
     * or [timeoutMs] elapses. Returns the byte count, or 0 on timeout.
     *
     * A timeout is not an error: the client polls a quiet link constantly.
     */
    fun read(buffer: ByteArray, timeoutMs: Int): Int

    /** Writes [data] in full. */
    fun write(data: ByteArray)

    /**
     * Whether the device is still physically present.
     *
     * Checked across a flash reboot, where the device drops off the bus and
     * re-enumerates as the bootloader.
     */
    fun isConnected(): Boolean
}

class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)
