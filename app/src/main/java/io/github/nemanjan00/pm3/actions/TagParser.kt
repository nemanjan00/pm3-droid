package io.github.nemanjan00.pm3.actions

/**
 * Parses the Proxmark client's human-readable output into something the UI can
 * render as fields.
 *
 * The client has no machine-readable mode, so this scrapes its print strings.
 * Every format here was read out of the client source rather than guessed, and
 * every field is optional: the client's wording changes between releases, and
 * a missing field must degrade to "not shown" rather than to a wrong value or
 * a crash. The raw output is always kept alongside so nothing is hidden.
 *
 * Output is plain ASCII: the client only emits ANSI colour when stdin *and*
 * stdout are both TTYs (proxmark3.c), and we always spawn it on pipes.
 */
object TagParser {

    data class LfTag(
        val type: String?,
        val chipset: String?,
        val id: String?,
        val extra: Map<String, String> = emptyMap(),
    ) {
        val isEmpty: Boolean get() = type == null && chipset == null && id == null
    }

    data class HfTag(
        val uid: String?,
        val atqa: String?,
        val sak: String?,
        val type: String?,
    ) {
        val isEmpty: Boolean get() = uid == null && atqa == null && sak == null
    }

    /**
     * `lf t55xx detect` / `info`.
     *
     * The client prints these as a dotted-leader block, e.g.
     * "[=]  Chip type......... T55x7". Kept as an ordered map rather than
     * named fields: the set of rows varies with the chip and the downlink
     * mode, and an unknown row is worth showing verbatim rather than dropping.
     */
    data class T55xx(val fields: Map<String, String>) {
        val isEmpty: Boolean get() = fields.isEmpty()

        val chipType: String? get() = fields["Chip type"]
        val modulation: String? get() = fields["Modulation"]
        val block0: String? get() = fields["Block0"]

        /**
         * A password-protected T55xx will not take a write without the
         * password, and writing blind can lock the card out permanently.
         */
        val passwordSet: Boolean get() = fields["Password set"]?.startsWith("Yes") == true
    }

    data class Antenna(
        val lfVoltage: String?,
        val lfOptimalDivisor: String?,
        val hfVoltage: String?,
        val lfVerdict: String?,
        val hfVerdict: String?,
    )

    /**
     * `lf search`.
     *
     * Looks for "Valid <type> ID found!" (cmdlf.c) and "Chipset... <name>".
     * Note the chipset line is "Chipset... ", not the "Chipset detection: " an
     * older client used -- matching the old form silently yields no chipset.
     */
    fun lfSearch(output: String): LfTag {
        val type = Regex("""Valid\s+(.+?)\s+ID found""").find(output)?.groupValues?.get(1)?.trim()
        val chipset = Regex("""Chipset\.*\s*(.+)""").find(output)?.groupValues?.get(1)?.trim()

        // Each LF scheme prints its id differently; take the first that hits.
        val id = ID_PATTERNS.firstNotNullOfOrNull { pattern ->
            pattern.find(output)?.groupValues?.get(1)?.trim()
        }

        val extra = buildMap {
            Regex("""Electra\s+(\d+)""").find(output)?.let { put("Electra", it.groupValues[1]) }
            Regex("""FC:\s*(\d+)""").find(output)?.let { put("Facility code", it.groupValues[1]) }
            Regex("""Card:\s*(\d+)""").find(output)?.let { put("Card number", it.groupValues[1]) }
        }

        return LfTag(type, chipset, id, extra)
    }

    /** `hf 14a info` / `hf search`. Formats from cmdhf14a.c. */
    fun hf14aInfo(output: String): HfTag {
        val uid = Regex("""(?m)^\s*\[[+=]\]\s*UID:\s*([0-9A-Fa-f ]+)""").find(output)
            ?.groupValues?.get(1)?.trim()?.replace(Regex("\\s+"), " ")
        val atqa = Regex("""(?m)ATQA:\s*([0-9A-Fa-f ]+)""").find(output)
            ?.groupValues?.get(1)?.trim()
        val sak = Regex("""(?m)SAK:\s*([0-9A-Fa-f]+)""").find(output)?.groupValues?.get(1)?.trim()
        // The client prints "Possible types:" as a bare header and the name
        // on the *following* line, so a same-line match finds nothing. Accept
        // either shape: the header-plus-next-line form, or an inline "Type:".
        val type = Regex("""(?m)^\s*\[[+=]\]\s*Possible types?:\s*$\s*^\s*\[[+=]\]\s*(.+)$""")
            .find(output)?.groupValues?.get(1)?.trim()
            ?: Regex("""(?m)^\s*\[[+=]\]\s*(?:Possible types?|Type):\s*(\S.*)$""")
                .find(output)?.groupValues?.get(1)?.trim()
        return HfTag(uid, atqa, sak, type)
    }

    /** `hw tune`. Voltages are what tell you the antenna is actually usable. */
    fun hwTune(output: String): Antenna = Antenna(
        lfVoltage = Regex("""LF antenna:?\s*([\d.]+\s*V)""").find(output)?.groupValues?.get(1),
        lfOptimalDivisor = Regex("""optimal\s*divisor\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(output)?.groupValues?.get(1),
        hfVoltage = Regex("""HF antenna:?\s*([\d.]+\s*V)""").find(output)?.groupValues?.get(1),
        lfVerdict = Regex("""LF antenna\.*\s*(.+)""").find(output)?.groupValues?.get(1)?.trim(),
        hfVerdict = Regex("""HF antenna\s*\(\s*(.+?)\s*\)""").find(output)?.groupValues?.get(1),
    )

    /**
     * Parses the key/value block the T55xx commands print.
     *
     * Two shapes exist and both turn up in normal use:
     *
     *   `lf t55xx detect`  "[=]  Chip type......... T55x7"
     *   `lf t55xx info`    "[=]  Safer key                 : 0"
     *
     * The dotted form also carries the fingerprint line, "[+] Config block
     * match... EM unique, Paxton", which is the most useful row of the lot.
     *
     * Rule/header lines ("--- Fingerprint ------------", a run of dashes) and
     * valueless rows are skipped rather than stored as junk fields.
     */
    fun t55xx(output: String): T55xx {
        val fields = LinkedHashMap<String, String>()

        for (raw in output.lineSequence()) {
            // Strip the client's severity marker.
            val line = MARKER.replace(raw, "").trim()
            if (line.isEmpty()) continue
            // Section rules and headers, e.g. "--- Fingerprint ------------".
            if (line.startsWith("-")) continue

            val match = DOTTED.find(line) ?: COLON.find(line) ?: continue
            val label = match.groupValues[1].trim().trimEnd('.', ':').trim()
            val value = match.groupValues[2].trim()
            if (label.isEmpty() || value.isEmpty()) continue
            if (label.startsWith("-")) continue
            fields[label] = value
        }
        return T55xx(fields)
    }

    /** True when the client reported no tag rather than an error. */
    fun foundNothing(output: String): Boolean =
        output.contains("No known 125/134 kHz tags found") ||
            output.contains("No data found") ||
            output.contains("Unknown") && !output.contains("Valid")

    /** The client's "[=] " / "[+] " severity marker. */
    private val MARKER = Regex("""^\s*\[[=+!\-]{1,2}\]\s*""")

    /** "Chip type......... T55x7" */
    private val DOTTED = Regex("""^(\S.*?)\.{3,}\s*(.*)${'$'}""")

    /** "Safer key                 : 0" */
    private val COLON = Regex("""^(\S[^:]*?)\s*:\s+(.*)${'$'}""")

    private val ID_PATTERNS = listOf(
        Regex("""EM 410x ID\s+([0-9A-Fa-f]+)"""),
        Regex("""HID Prox TAG ID:\s*([0-9A-Fa-f]+)"""),
        Regex("""Indala \(len \d+\)\s+Raw:\s*([0-9A-Fa-f]+)"""),
        Regex("""FDX-B ID:\s*([0-9A-Fa-f]+)"""),
        Regex("""Viking ID\s+([0-9A-Fa-f]+)"""),
        Regex("""Jablotron Card ID:\s*([0-9A-Fa-f]+)"""),
        Regex("""(?m)^\s*\[\+\].*\bID:?\s+([0-9A-Fa-f]{8,})"""),
    )
}
