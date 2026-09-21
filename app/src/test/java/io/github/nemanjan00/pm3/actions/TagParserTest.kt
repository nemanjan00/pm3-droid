package io.github.nemanjan00.pm3.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parsers scrape the client's human-readable output, so the samples below
 * are real client text, in the shape the current RRG/Iceman client prints it.
 *
 * Plain ASCII on purpose: the client only emits ANSI colour when stdin and
 * stdout are both TTYs, and the app always spawns it on pipes.
 */
class TagParserTest {

    private val lfEm410x = """
        [=] Checking for known tags...
        [=]
        [+] EM 410x ID 0F0368568B
        [+] EM410x ( RF/64 )
        [=] -------- Possible de-scramble patterns ---------
        [+] Unique TAG ID      : F0C0166AD1
        [+] HoneyWell IdentKey
        [=] ---------------------------------------
        [+] Valid EM410x ID found!
        [+] Chipset... T55xx
    """.trimIndent()

    private val hf14a = """
        [+]  UID: 04 A2 26 B2 4F 5C 80
        [+] ATQA: 00 44
        [+]  SAK: 00 [2]
        [+] Possible types:
        [+]    MIFARE Ultralight EV1 48bytes
    """.trimIndent()

    private val nothing = """
        [=] Checking for known tags...
        [-] No known 125/134 kHz tags found!
    """.trimIndent()

    @Test
    fun `reads an EM410x id and type`() {
        val tag = TagParser.lfSearch(lfEm410x)
        assertEquals("EM410x", tag.type)
        assertEquals("0F0368568B", tag.id)
    }

    @Test
    fun `reads the chipset from the current wording`() {
        // The older client said "Chipset detection: "; today it is
        // "Chipset... ". Matching only the old form yields a silent null.
        assertEquals("T55xx", TagParser.lfSearch(lfEm410x).chipset)
    }

    @Test
    fun `reports an empty tag when nothing is on the antenna`() {
        val tag = TagParser.lfSearch(nothing)
        assertTrue(tag.isEmpty)
        assertTrue(TagParser.foundNothing(nothing))
    }

    @Test
    fun `reads uid atqa and sak`() {
        val tag = TagParser.hf14aInfo(hf14a)
        assertEquals("04 A2 26 B2 4F 5C 80", tag.uid)
        assertEquals("00 44", tag.atqa)
        assertEquals("00", tag.sak)
    }

    @Test
    fun `reads the product name printed under Possible types`() {
        // The name is on the line *after* the "Possible types:" header, which
        // a naive same-line match misses entirely.
        assertEquals("MIFARE Ultralight EV1 48bytes", TagParser.hf14aInfo(hf14a).type)
    }

    @Test
    fun `an lf sample yields no hf fields`() {
        // The Actions screen parses every result both ways, so a parser that
        // matches too loosely would invent HF fields for an LF tag.
        assertTrue(TagParser.hf14aInfo(lfEm410x).isEmpty)
    }


    // Real output from `lf t55xx detect` on a T5577.
    private val t55Detect = """
        [=]  Chip type......... T55x7
        [=]  Modulation........ ASK
        [=]  Bit rate.......... 5 - RF/64
        [=]  Inverted.......... No
        [=]  Offset............ 33
        [=]  Seq. terminator... Yes
        [=]  Block0............ 00148040 (auto detect)
        [=]  Downlink mode..... default/fixed bit length
        [=]  Password set...... No
    """.trimIndent()

    // Real output from `lf t55xx info` on the same card.
    private val t55Info = """
        [=] --- T55x7 Configuration & Information ---------
        [=]  Safer key                 : 0
        [=]  reserved                  : 0
        [=]  Data bit rate             : 5 - RF/64
        [=]  eXtended mode             : No
        [=]  Modulation                : 8 - Manchester
        [=]  PSK clock frequency       : 0 - RF/2
        [=]  AOR - Answer on Request   : No
        [=]  OTP - One Time Pad        : No
        [=]  Max block                 : 2
        [=]  Password mode             : No
        [=]  Sequence Terminator       : No
        [=]  Fast Write                : No
        [=]  Inverse data              : No
        [=]  POR-Delay                 : No
        [=] -------------------------------------------------------------
        [=]  Raw Data - Page 0, block 0
        [=]  00148040 - 00000000000101001000000001000000
        [=] --- Fingerprint ------------
        [+] Config block match... EM unique, Paxton
    """.trimIndent()

    @Test
    fun `parses the dotted detect block`() {
        val t = TagParser.t55xx(t55Detect)
        assertEquals("T55x7", t.chipType)
        assertEquals("ASK", t.modulation)
        assertEquals("00148040 (auto detect)", t.block0)
        assertEquals("5 - RF/64", t.fields["Bit rate"])
        // The label keeps its internal full stop; only the leader is stripped.
        assertEquals("Yes", t.fields["Seq. terminator"])
        assertTrue(!t.passwordSet)
    }

    @Test
    fun `parses the colon separated info block`() {
        val t = TagParser.t55xx(t55Info)
        assertEquals("8 - Manchester", t.fields["Modulation"])
        assertEquals("5 - RF/64", t.fields["Data bit rate"])
        assertEquals("2", t.fields["Max block"])
        // A label containing a dash and spaces must survive intact.
        assertEquals("No", t.fields["AOR - Answer on Request"])
        assertEquals("No", t.fields["OTP - One Time Pad"])
    }

    @Test
    fun `keeps the fingerprint line`() {
        // The most useful row: what the config block looks like it is for.
        assertEquals("EM unique, Paxton", TagParser.t55xx(t55Info).fields["Config block match"])
    }

    @Test
    fun `skips rules headers and valueless rows`() {
        val t = TagParser.t55xx(t55Info)
        assertTrue(t.fields.keys.none { it.startsWith("-") })
        // "Raw Data - Page 0, block 0" has no separator and must not become a
        // field; nor may the dashed rule lines.
        assertTrue(t.fields.keys.none { it.contains("Raw Data") })
        assertNull(t.fields["T55x7 Configuration & Information"])
    }

    @Test
    fun `password mode is surfaced`() {
        val locked = t55Detect.replace("Password set...... No", "Password set...... Yes")
        assertTrue(TagParser.t55xx(locked).passwordSet)
    }


    // Real output from a MIFARE Classic 1K. The UID line carries a trailing
    // annotation that must not end up in the UID.
    private val hf14aOnuid = """
        [=] ---------- ISO14443-A Information ----------
        [+]  UID: FE 7C FA E5   ( ONUID, re-used )
        [+] ATQA: 00 04
        [+]  SAK: 08 [2]
        [+] Possible types:
        [+]    MIFARE Classic 1K
        [=] 
        [=] Proprietary non iso14443-4 card found
        [=] RATS not supported
    """.trimIndent()

    @Test
    fun `uid stops before a trailing annotation`() {
        val tag = TagParser.hf14aInfo(hf14aOnuid)
        assertEquals("FE 7C FA E5", tag.uid)
        assertEquals("00 04", tag.atqa)
        assertEquals("08", tag.sak)
        assertEquals("MIFARE Classic 1K", tag.type)
    }

    @Test
    fun `survives empty and garbage input`() {
        assertTrue(TagParser.lfSearch("").isEmpty)
        assertTrue(TagParser.hf14aInfo("").isEmpty)
        assertNull(TagParser.hwTune("").lfVoltage)
        assertTrue(TagParser.t55xx("").isEmpty)
        assertTrue(TagParser.lfSearch("not output at all {[}").isEmpty)
    }
}
