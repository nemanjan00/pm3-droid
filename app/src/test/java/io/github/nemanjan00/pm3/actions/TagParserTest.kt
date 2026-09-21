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

    @Test
    fun `survives empty and garbage input`() {
        assertTrue(TagParser.lfSearch("").isEmpty)
        assertTrue(TagParser.hf14aInfo("").isEmpty)
        assertNull(TagParser.hwTune("").lfVoltage)
        assertTrue(TagParser.lfSearch("not output at all {[}").isEmpty)
    }
}
