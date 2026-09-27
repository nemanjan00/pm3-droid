package io.github.nemanjan00.pm3.flash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every line here came from a real failed flash of a Proxmark5 over the BWM
 * and over USB, not from reading the client source. The point of the parser is
 * to turn those into something actionable, so the tests hold it to the actual
 * wording.
 */
class FlashProgressParserTest {

    @Test
    fun `follows the stages of a normal run`() {
        val p = FlashProgressParser
        assertEquals(FlashProgressParser.Stage.PREPARING,
            p.stageOf("[+] Loading ELF file /data/.../fullimage.elf"))
        assertEquals(FlashProgressParser.Stage.WAITING_FOR_DEVICE,
            p.stageOf("[+] Waiting for Proxmark3 to appear on tcp:127.0.0.1:18888"))
        assertEquals(FlashProgressParser.Stage.ENTERING_BOOTLOADER,
            p.stageOf("[+] Entering bootloader..."))
        assertEquals(FlashProgressParser.Stage.ENTERING_BOOTLOADER,
            p.stageOf("[+] Trigger restart..."))
        assertEquals(FlashProgressParser.Stage.DONE, p.stageOf("[+] All done"))
    }

    @Test
    fun `ordinary chatter is not a stage`() {
        assertNull(FlashProgressParser.stageOf("[+] (Press and release the button only to abort)"))
        assertNull(FlashProgressParser.stageOf(""))
    }

    @Test
    fun `wireless refusal explains that USB is required`() {
        val f = FlashProgressParser.failureOf(
            "[!!] This bootloader cannot flash over a wireless link (flags 0x000001f7)"
        )!!
        assertTrue(f.summary.contains("wireless"))
        assertTrue(f.whatToDo.contains("USB"))
        // Not worth offering a retry over the same link.
        assertTrue(!f.retryable)
    }

    @Test
    fun `lost ACK is retryable and says the device is safe`() {
        val f = FlashProgressParser.failureOf("[!!] No ACK from bootloader (timeout)")!!
        assertTrue(f.retryable)
        assertTrue(f.deviceInBootloader)
        // The reassurance matters: a stalled write reads as a bricked device.
        assertTrue(f.whatToDo.contains("nothing is lost"))
    }

    @Test
    fun `procedure failure tells the user how to leave flash mode`() {
        val f = FlashProgressParser.failureOf(
            "[!] The flashing procedure failed, follow the suggested steps!"
        )!!
        assertTrue(f.whatToDo.contains("Short-press"))
        assertTrue(f.retryable)
    }

    @Test
    fun `success lines are not failures`() {
        assertNull(FlashProgressParser.failureOf("[+] All done"))
        assertNull(FlashProgressParser.failureOf("[+] Entering bootloader..."))
    }

    @Test
    fun `reads a percentage from the progress bar`() {
        assertEquals(0, FlashProgressParser.percentOf(" Flashing [-----------] 0%"))
        assertEquals(42, FlashProgressParser.percentOf("[=] Writing... 42%"))
        assertNull(FlashProgressParser.percentOf("[+] no percentage here"))
        // A block address must not be mistaken for progress.
        assertNull(FlashProgressParser.percentOf("[!] No ACK at 0x08004800 (packet 1)"))
    }

    @Test
    fun `detects a device left in flash mode`() {
        val tail = """
            [=] Nothing was written. Short-press the button to leave flash mode.
            [!] The flashing procedure failed, follow the suggested steps!
        """.trimIndent()
        assertTrue(FlashProgressParser.leftInBootloader(tail))
        assertTrue(!FlashProgressParser.leftInBootloader("[+] All done"))
    }
}
