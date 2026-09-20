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
        data class Step(val label: String, val fraction: Float?) : Progress
        data class Finished(val success: Boolean, val message: String) : Progress
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
            .also { it.environment().putAll(runtime.environment()) }
            .redirectErrorStream(true)
            .start()

        val reader = process.inputStream.bufferedReader()
        var sawError = false

        reader.forEachLine { line ->
            trySend(Progress.Line(line))
            parseProgress(line)?.let { trySend(it) }
            if (ERROR_MARKERS.any { line.contains(it) }) sawError = true
        }

        val code = process.waitFor()
        val ok = code == 0 && !sawError
        trySend(
            Progress.Finished(
                success = ok,
                message = if (ok) {
                    "Flash complete. The device reboots and re-enumerates on USB; " +
                        "reconnect the bridge before using it."
                } else {
                    "Flash failed (exit $code). The device is most likely still in " +
                        "bootloader mode -- it is safe to retry."
                },
            )
        )
        close()
        awaitClose { process.destroy() }
    }.flowOn(Dispatchers.IO)

    /**
     * Pulls a percentage out of the client's flash output.
     *
     * The client prints a bar like `[=] Writing... 42%`, so the percent sign is
     * the reliable anchor; the label is whatever precedes it.
     */
    private fun parseProgress(line: String): Progress.Step? {
        val match = PERCENT.find(line) ?: return null
        val pct = match.groupValues[1].toFloatOrNull() ?: return null
        val label = line.substringBefore(match.value)
            .trim()
            .removePrefix("[=]").removePrefix("[+]").removePrefix("[#]")
            .trim()
            .ifEmpty { "Flashing" }
        return Progress.Step(label, (pct / 100f).coerceIn(0f, 1f))
    }

    companion object {
        private val PERCENT = Regex("""(\d{1,3})\s*%""")

        private val ERROR_MARKERS = listOf(
            "[!!]",
            "Error:",
            "ERROR:",
            "Bootloader version does not support",
            "cannot communicate with the Proxmark3",
        )
    }
}
