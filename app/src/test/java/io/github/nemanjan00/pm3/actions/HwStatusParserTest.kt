package io.github.nemanjan00.pm3.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real `hw status` output from a Proxmark5 with a BWM fitted. */
class HwStatusParserTest {

    private val status = """
[#] Memory
[#]   BigBuf_size............. 406180
[#]   Available memory........ 405920
[#]   Emulator memory......... 8192 ( not allocated )
[#] Tracing
[#]   tracing ................ no
[#]   traceLen ............... 20
[#] Current FPGA image
[#]   mode.................... All-In-One
[#] Flash memory
[#]   Mfr ID / Dev ID......... EF / 14
[#]   Memory size............. 2048 Kb ( 32 pages * 64k )
[#] LF Sampling config
[#]   [q] divisor............. 95 ( 125.00 kHz )
[#]   [b] bits per sample..... 8
[#] 
[#] LF T55XX config
[#]            [r]               [a]   [b]   [c]   [d]   [e]   [f]   [g]
[#]            mode            |start|write|write|write| read|write|write
[#] ---------------------------+-----+-----+-----+-----+-----+-----+------
[#] fixed bit length (default) |  29 |  17 |  15 |  47 |  15 | n/a | n/a | 
[#] 
[#] Battery / BWM
[#]   Charge status....... charging, power good
[#]   Battery SoC......... 71 %
[#]   Battery voltage..... 4044 mV
[#]   Temp (gauge)........ 49.6 C
[#]   BWM BLE............. advertising
[#] Power
[#]   Core clock.......... 288 MHz active, 48 MHz idle ( PLL off, LDO 1.1 V )
[#]   Uptime.............. 209778 ms
[#] Installed StandAlone Mode
[#]   LF HID26 standalone - aka SamyRun (Samy Kamkar)
[#] Flash memory dictionary loaded
[#]   Mifare... 2511 keys - dict_mf.bin
[#]   T55xx.... 125 keys - dict_t55xx.bin
[#]
    """.trimIndent()

    @Test
    fun `groups rows under their section`() {
        val s = TagParser.hwStatus(status)
        assertEquals("406180", s["Memory"]["BigBuf_size"])
        assertEquals("no", s["Tracing"]["tracing"])
        assertEquals("All-In-One", s["Current FPGA image"]["mode"])
    }

    @Test
    fun `keeps parenthetical annotations in the value`() {
        val s = TagParser.hwStatus(status)
        assertEquals("8192 ( not allocated )", s["Memory"]["Emulator memory"])
        assertEquals("2048 Kb ( 32 pages * 64k )", s["Flash memory"]["Memory size"])
    }

    @Test
    fun `keeps the option letter in the label`() {
        // "[q]" is the key that changes the setting, so it is worth showing.
        val s = TagParser.hwStatus(status)
        assertEquals("95 ( 125.00 kHz )", s["LF Sampling config"]["[q] divisor"])
    }

    @Test
    fun `reads battery state`() {
        val s = TagParser.hwStatus(status)
        assertEquals(71, s.batteryPercent)
        assertTrue(s.charging)
        assertEquals("4044 mV", s.battery["Battery voltage"])
        assertEquals("advertising", s.battery["BWM BLE"])
    }

    @Test
    fun `skips the t55xx timing grid`() {
        // A table of columns is not key/value; forcing it into one would
        // invent fields. The section drops out entirely once its rows are
        // skipped, rather than appearing empty.
        val s = TagParser.hwStatus(status)
        assertTrue("LF T55XX config" !in s.sections)
        assertTrue(s.sections.values.none { it.isEmpty() })
    }

    @Test
    fun `handles a value containing a slash and spaces`() {
        assertEquals("EF / 14", TagParser.hwStatus(status)["Flash memory"]["Mfr ID / Dev ID"])
    }

    @Test
    fun `reads dictionary counts`() {
        val d = TagParser.hwStatus(status)["Flash memory dictionary loaded"]
        assertEquals("2511 keys - dict_mf.bin", d["Mifare"])
        assertEquals("125 keys - dict_t55xx.bin", d["T55xx"])
    }

    @Test
    fun `empty input yields nothing`() {
        assertTrue(TagParser.hwStatus("").isEmpty)
    }
}
