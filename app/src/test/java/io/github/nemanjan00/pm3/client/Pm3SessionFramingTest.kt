package io.github.nemanjan00.pm3.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Framing of the Lua shim's control objects.
 *
 * The case that matters is a sentinel appended to a partial line: the client
 * prints progress with PrintAndLogEx(INPLACE, ...), which leaves text on the
 * line with no trailing newline. A parser anchored at position 0 loses the
 * object entirely, and a lost command_end hangs the caller forever -- which
 * is exactly how this was found, with the UI spinning after `lf search`.
 */
class Pm3SessionFramingTest {

    private fun split(line: String) = Pm3Session.splitControl(line)

    @Test
    fun `parses a control object on its own line`() {
        val (prefix, obj) = split("""{"type":"command_end","ok":true}""")!!
        assertEquals("", prefix)
        assertEquals("command_end", obj.optString("type"))
    }

    @Test
    fun `parses a sentinel appended to a partial progress line`() {
        val line = """[=] Searching for auth LF and special cases...{"type":"command_end","ok":true}"""
        val (prefix, obj) = split(line)!!
        assertEquals("[=] Searching for auth LF and special cases...", prefix)
        assertEquals("command_end", obj.optString("type"))
    }

    @Test
    fun `parses started even when prefixed`() {
        val (_, obj) = split("""[+] args ''{"type":"started"}""")!!
        assertEquals("started", obj.optString("type"))
    }

    @Test
    fun `ignores ordinary output`() {
        assertNull(split("[+] EM 410x ID FFFFFFFFFF"))
        assertNull(split(""))
    }

    @Test
    fun `ignores json that is not one of ours`() {
        // Command output can legitimately contain JSON; only the shim's own
        // type tags count as control.
        assertNull(split("""[+] dump: {"uid":"04A226","blocks":16}"""))
    }

    @Test
    fun `finds the object past an earlier brace`() {
        val line = """[+] {not json} trailing {"type":"command_end","ok":false}"""
        val (prefix, obj) = split(line)!!
        assertEquals("command_end", obj.optString("type"))
        assertEquals("[+] {not json} trailing ", prefix)
    }
}
