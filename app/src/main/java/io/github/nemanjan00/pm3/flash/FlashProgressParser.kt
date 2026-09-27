package io.github.nemanjan00.pm3.flash

/**
 * Turns the flasher's output into a stage plus, when it fails, something the
 * user can act on.
 *
 * Flashing is the one operation here that can leave hardware unusable, and its
 * failures are recoverable but only if you know what to do -- a device left in
 * bootloader mode looks dead until someone tells you to short-press the
 * button. The client says all of this, buried in its log; this lifts it out.
 *
 * Every string below was taken from a real flash session, not guessed.
 */
object FlashProgressParser {

    /** Where the flash has got to. Ordered, so the UI can render a stepper. */
    enum class Stage(val label: String) {
        PREPARING("Reading the firmware file"),
        WAITING_FOR_DEVICE("Waiting for the Proxmark"),
        ENTERING_BOOTLOADER("Entering the bootloader"),
        WAITING_FOR_BOOTLOADER("Waiting for it to come back"),
        WRITING("Writing firmware"),
        VERIFYING("Verifying"),
        DONE("Finished"),
    }

    /**
     * A failure, with what to do about it.
     *
     * [deviceInBootloader] matters: it means the device is sitting in flash
     * mode rather than broken, and the fix is a button press.
     */
    data class Failure(
        val summary: String,
        val whatToDo: String,
        val deviceInBootloader: Boolean,
        val retryable: Boolean,
    )

    fun stageOf(line: String): Stage? = when {
        line.contains("Loading ELF file") || line.contains("About to use the following file") ->
            Stage.PREPARING
        line.contains("Entering bootloader") || line.contains("Trigger restart") ->
            Stage.ENTERING_BOOTLOADER
        // The same "Waiting" line appears before and after the reboot; which
        // one it is depends on whether the bootloader has been entered yet, so
        // the caller keeps the highest stage seen.
        line.contains("Waiting for Proxmark3 to appear") ->
            Stage.WAITING_FOR_DEVICE
        line.contains("Writing") || Regex("""\bFlashing\b""").containsMatchIn(line) ->
            Stage.WRITING
        line.contains("Verifying") || line.contains("Validating") ->
            Stage.VERIFYING
        line.contains("All done") || line.contains("Have a nice day") ->
            Stage.DONE
        else -> null
    }

    /** A failure and its remedy, or null if this line is not a failure. */
    fun failureOf(line: String): Failure? = when {
        line.contains("cannot flash over a wireless link") -> Failure(
            summary = "This bootloader will not flash over a wireless link.",
            whatToDo = "Connect the Proxmark by USB-OTG and flash again. " +
                "Bluetooth and the BWM can read and write tags, but not " +
                "firmware — the bootloader refuses it.",
            deviceInBootloader = true,
            retryable = false,
        )

        line.contains("No ACK from bootloader") -> Failure(
            summary = "The bootloader stopped responding mid-write.",
            whatToDo = "The device is still in bootloader mode, so nothing is " +
                "lost. Check the cable and the OTG adapter, then flash again. " +
                "A hub with its own power supply helps — some phones cannot " +
                "supply enough current on their own.",
            deviceInBootloader = true,
            retryable = true,
        )

        line.contains("Bootloader version does not support") -> Failure(
            summary = "This bootloader is too old for the image.",
            whatToDo = "Flash the bootloader first from a PC over USB, then " +
                "come back for the firmware.",
            deviceInBootloader = true,
            retryable = false,
        )

        line.contains("cannot communicate with the Proxmark3") ||
            line.contains("Could not connect") && line.contains("error") -> Failure(
            summary = "Lost contact with the Proxmark.",
            whatToDo = "It re-enumerates on USB when it enters the bootloader, " +
                "so Android may ask for USB permission again — accept it and " +
                "retry. If nothing appears, unplug and replug.",
            deviceInBootloader = true,
            retryable = true,
        )

        line.contains("The flashing procedure failed") -> Failure(
            summary = "The flash did not complete.",
            whatToDo = "Nothing was written, so the device still has its old " +
                "firmware. Short-press the button to leave flash mode, then " +
                "try again.",
            deviceInBootloader = true,
            retryable = true,
        )

        else -> null
    }

    /**
     * Percentage from the client's progress line.
     *
     * The bar is drawn with cursor addressing when it thinks it has a
     * terminal; we suppress that at build time, but a stray percentage can
     * still appear, so the anchor is the percent sign rather than the layout.
     */
    fun percentOf(line: String): Int? =
        Regex("""(\d{1,3})\s*%""").find(line)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 0..100 }

    /** True once the device is in flash mode and a button press is needed to leave. */
    fun leftInBootloader(output: String): Boolean =
        output.contains("Short-press the button to leave flash mode")
}
