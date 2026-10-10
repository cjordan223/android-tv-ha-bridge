package nz.mcnabb.atvhabridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneControlTest {
    @Test
    fun `a tap is two fractions of the screen`() {
        assertEquals(0.5 to 0.25, tapPoint("tap:0.5,0.25"))
        assertEquals(0.0 to 1.0, tapPoint("tap:0,1"))
        assertEquals(0.125 to 0.0, tapPoint("tap:.125,0.0"))
        assertNull(tapPoint("tap:1.2,0.5"))       // off the screen
        assertNull(tapPoint("tap:-0.1,0.5"))
        assertNull(tapPoint("tap:0.5"))
        assertNull(tapPoint("tap:0.5,0.5,0.5"))
        assertNull(tapPoint("tap: 0.5,0.5"))
        assertNull(tapPoint("dpad_up"))
    }

    @Test
    fun `typed text is one line, capped, and may be empty`() {
        assertEquals("bluey", typedText("bluey"))
        assertEquals("", typedText(""))           // clears the box
        assertEquals("two words", typedText("two words"))
        assertEquals("ab", typedText("a\nb"))      // no newlines or other control characters
        assertEquals("Māori ✓", typedText("Māori ✓"))
        assertEquals(500, typedText("x".repeat(500))?.length)
        assertNull(typedText("x".repeat(501)))
    }

    @Test
    fun `the screen grant never bounces over something playing`() {
        val gap = 20_000L
        assertTrue(grantAllowed(interactive = true, playing = false, sinceLastMs = 0, triedBefore = false, gapMs = gap))
        assertFalse(grantAllowed(interactive = true, playing = true, sinceLastMs = 60_000, triedBefore = true, gapMs = gap))
        assertFalse(grantAllowed(interactive = false, playing = false, sinceLastMs = 60_000, triedBefore = true, gapMs = gap))
        assertFalse(grantAllowed(interactive = true, playing = false, sinceLastMs = 5_000, triedBefore = true, gapMs = gap))
        assertTrue(grantAllowed(interactive = true, playing = false, sinceLastMs = 20_000, triedBefore = true, gapMs = gap))
    }
}
