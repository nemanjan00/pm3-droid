package io.github.nemanjan00.pm3.actions

/**
 * A Proxmark operation offered in the UI.
 *
 * Declared as data rather than wired up per button so the screen stays a
 * simple list render, and so the destructive ones cannot quietly lose their
 * confirmation when someone adds another entry.
 */
data class Pm3Action(
    val id: String,
    val label: String,
    val description: String,
    /** The client command, e.g. "lf search". */
    val command: String,
    val group: Group,
    /**
     * Writes to a tag or to the device. These get a confirmation step: a
     * mistaken read costs a second, a mistaken write can permanently brick a
     * T55xx by setting a password or a bad config block.
     */
    val destructive: Boolean = false,
    /**
     * Roughly how long this runs, for the UI's expectation setting. Some
     * commands legitimately take minutes.
     */
    val slow: Boolean = false,
) {
    enum class Group(val title: String, val subtitle: String) {
        IDENTIFY("Identify", "Work out what is on the antenna"),
        LF("Low frequency", "125/134 kHz — EM410x, HID, T55xx"),
        HF("High frequency", "13.56 MHz — MIFARE, NTAG, ISO14443"),
        DEVICE("Device", "Status and diagnostics"),
    }

    companion object {
        val ALL: List<Pm3Action> = listOf(
            Pm3Action(
                id = "auto",
                label = "Identify anything",
                description = "Runs the full LF then HF search. Slowest, but " +
                    "the right first move on an unknown card.",
                command = "auto",
                group = Group.IDENTIFY,
                slow = true,
            ),
            Pm3Action(
                id = "lf_search",
                label = "LF search",
                description = "Identify a 125/134 kHz tag.",
                command = "lf search",
                group = Group.IDENTIFY,
            ),
            Pm3Action(
                id = "hf_search",
                label = "HF search",
                description = "Identify a 13.56 MHz tag.",
                command = "hf search",
                group = Group.IDENTIFY,
            ),

            Pm3Action(
                id = "lf_em_read",
                label = "Read EM410x",
                description = "Read an EM410x tag's ID.",
                command = "lf em 410x reader",
                group = Group.LF,
            ),
            Pm3Action(
                id = "lf_t55_detect",
                label = "Detect T55xx",
                description = "Read the config block of a T55xx. Worth doing " +
                    "before any write, to see the modulation already set.",
                command = "lf t55xx detect",
                group = Group.LF,
            ),
            Pm3Action(
                id = "lf_t55_info",
                label = "T55xx config",
                description = "Decode the T55xx configuration block.",
                command = "lf t55xx info",
                group = Group.LF,
            ),
            Pm3Action(
                id = "lf_hid_read",
                label = "Read HID Prox",
                description = "Read an HID Prox credential.",
                command = "lf hid reader",
                group = Group.LF,
            ),

            Pm3Action(
                id = "hf_14a_info",
                label = "ISO14443-A info",
                description = "UID, ATQA, SAK and a guess at the product.",
                command = "hf 14a info",
                group = Group.HF,
            ),
            Pm3Action(
                id = "hf_mf_info",
                label = "MIFARE info",
                description = "Identify a MIFARE Classic and check for known " +
                    "weaknesses.",
                command = "hf mf info",
                group = Group.HF,
            ),
            Pm3Action(
                id = "hf_mf_chk",
                label = "Test default keys",
                description = "Try the bundled dictionary against every " +
                    "sector. Read-only.",
                command = "hf mf chk --1k -f mfc_default_keys",
                group = Group.HF,
                slow = true,
            ),
            Pm3Action(
                id = "hf_mfu_info",
                label = "MIFARE Ultralight / NTAG info",
                description = "Version, signature and config pages.",
                command = "hf mfu info",
                group = Group.HF,
            ),

            Pm3Action(
                id = "hw_status",
                label = "Device status",
                description = "Firmware build, memory and clock.",
                command = "hw status",
                group = Group.DEVICE,
            ),
            Pm3Action(
                id = "hw_version",
                label = "Version",
                description = "Client and firmware versions. Worth checking " +
                    "first when something behaves oddly — a mismatch between " +
                    "the two causes exactly that.",
                command = "hw version",
                group = Group.DEVICE,
            ),
            Pm3Action(
                id = "hw_tune",
                label = "Tune antennas",
                description = "Measure LF and HF antenna voltages. The first " +
                    "thing to check when nothing reads.",
                command = "hw tune",
                group = Group.DEVICE,
                slow = true,
            ),
        )

        fun byGroup(): Map<Group, List<Pm3Action>> = ALL.groupBy { it.group }
    }
}
