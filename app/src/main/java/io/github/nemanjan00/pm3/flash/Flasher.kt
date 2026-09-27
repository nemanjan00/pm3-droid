package io.github.nemanjan00.pm3.flash

import io.github.nemanjan00.pm3.client.Pm3Runtime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.channels.awaitClose
import java.io.File

/**
 * Flashes firmware by driving the bundled client's own `--flash` mode.
 *
 * Reimplementing the bootloader protocol here would mean re-deriving the
 * chip-specific erase/write/verify sequences for AT91SAM7S, AT32F435 and the
 * ICOPY-X variants -- the client already has all of that and stays correct as
 * upstream changes it. So the flasher shells out and parses progress.
 *
 * USB only, deliberately: see [io.github.nemanjan00.pm3.transport.Transport.supportsFlashing].
 */
class Flasher(private val runtime: Pm3Runtime) {

    sealed interface Progress {
        data class Line(val text: String) : Progress
        /** [fraction] in 0..1, or null when the client reports no percentage. */
        data class Step(
            val label: String,
            val fraction: Float?,
            val stage: FlashProgressParser.Stage? = null,
        ) : Progress
        data class Finished(
            val success: Boolean,
            val message: String,
            /** Present on failure: what went wrong and what to do about it. */
            val failure: FlashProgressParser.Failure? = null,
        ) : Progress
    }

    /**
     * Writes [image] to the device reachable at [address].
     *
     * @param image      a fullimage.elf or bootrom.elf from the firmware matrix
     * @param bootloader true to write the bootloader partition. Rarely right:
     *                   a failed bootloader write leaves a device that cannot
     *                   be recovered without JTAG, and upstream's own Android
     *                   notes advise against it over a phone link.
     */
    fun flash(
        address: String,
        image: File,
        bootloader: Boolean = false,
        wireless: Boolean = true,
    ): Flow<Progress> = callbackFlow {
        require(image.isFile) { "No such image: ${image.absolutePath}" }

        val args = buildList {
            add(runtime.binary.absolutePath)
            add(address)
            add("--flash")
            if (bootloader) {
                add("--unlock-bootloader")
                add("--image"); add(image.absolutePath)
            } else {
                add("--image"); add(image.absolutePath)
            }
        }

        val process = ProcessBuilder(args)
            .directory(runtime.home)
            // The client would otherwise read the loopback address as a
            // wireless link and refuse to flash a USB-attached device.
            .also { it.environment().putAll(runtime.environment(wireless)) }
            .redirectErrorStream(true)
            .start()

        val reader = process.inputStream.bufferedReader()
        var sawError = false
        var failure: FlashProgressParser.Failure? = null
        var stage: FlashProgressParser.Stage? = null

        reader.forEachLine { line ->
            trySend(Progress.Line(line))

            // Stages only advance. The "Waiting for Proxmark3" line appears
            // both before and after the reboot, so taking it at face value
            // would walk the stepper backwards halfway through a flash.
            FlashProgressParser.stageOf(line)?.let { seen ->
                if (stage == null || seen.ordinal > stage!!.ordinal) stage = seen
            }
            // Keep the first failure: later lines are usually its fallout, and
            // the first one names the actual cause.
            if (failure == null) failure = FlashProgressParser.failureOf(line)

            val pct = FlashProgressParser.percentOf(line)
            if (pct != null || FlashProgressParser.stageOf(line) != null) {
                trySend(
                    Progress.Step(
                        label = stage?.label ?: "Flashing",
                        fraction = pct?.let { it / 100f },
                        stage = stage,
                    )
                )
            }
            if (ERROR_MARKERS.any { line.contains(it) }) sawError = true
        }

        val code = process.waitFor()
        val ok = code == 0 && !sawError && failure == null
        trySend(
            Progress.Finished(
                success = ok,
                message = if (ok) {
                    "Flash complete. The device reboots and re-enumerates on " +
                        "USB, so reconnect on the Device tab before using it."
                } else {
                    failure?.summary ?: "The flash did not complete (exit $code)."
                },
                failure = failure,
            )
        )
        close()
        awaitClose { process.destroy() }
    }.flowOn(Dispatchers.IO)

    companion object {

        private val ERROR_MARKERS = listOf(
            "[!!]",
            "Error:",
            "ERROR:",
            "Bootloader version does not support",
            "cannot communicate with the Proxmark3",
        )
    }
}
