package dev.vector.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which voice each part of a sentence needs.
 *
 * Vector used to set the engine to Arabic once at startup, so a phone set to
 * en-GB read "Speed limit 80" and "Turn left onto Al Urouba Street" in an
 * Arabic voice. The fix has to keep two things true at once: an all-Latin
 * sentence must stay ONE utterance (splitting it would put audible gaps into
 * every instruction), and a genuinely mixed one must be split at the script
 * boundary and no more often than that.
 */
class VoiceGuideSegmentTest {

    private fun seg(s: String) = VoiceGuide.segment(s)

    @Test
    fun `an english instruction is one latin run`() {
        val runs = seg("Turn left onto Al Urouba Street")
        assertEquals(1, runs.size)
        assertFalse(runs[0].arabic)
        assertEquals("Turn left onto Al Urouba Street", runs[0].text)
    }

    @Test
    fun `the speed readout is one latin run`() {
        // The string SpeedAlert actually emits. It was being read in Arabic.
        val runs = seg("Speed limit 80")
        assertEquals(1, runs.size)
        assertFalse(runs[0].arabic)
    }

    @Test
    fun `an arabic street name is one arabic run`() {
        val runs = seg("شارع الكورنيش")
        assertEquals(1, runs.size)
        assertTrue(runs[0].arabic)
    }

    @Test
    fun `a mixed instruction splits at the script boundary`() {
        // The real shape for the 253 Qatar roads with no `name:en`.
        val runs = seg("Turn left onto شارع الخليج")
        assertEquals(2, runs.size)
        assertFalse(runs[0].arabic)
        assertEquals("Turn left onto", runs[0].text)
        assertTrue(runs[1].arabic)
        assertEquals("شارع الخليج", runs[1].text)
    }

    @Test
    fun `punctuation and digits do not start a run of their own`() {
        // Otherwise every comma in an instruction becomes an audible pause.
        val runs = seg("In 300 metres, turn left, then keep right")
        assertEquals(1, runs.size)
    }

    @Test
    fun `a trailing latin clause after arabic is its own run`() {
        val runs = seg("Continue on شارع الخليج then exit")
        assertEquals(3, runs.size)
        assertEquals(listOf(false, true, false), runs.map { it.arabic })
        assertEquals("then exit", runs[2].text)
    }

    @Test
    fun `a run of pure punctuation is dropped`() {
        assertEquals(emptyList<VoiceGuide.Run>(), seg("  ,  -- "))
    }

    @Test
    fun `empty text says nothing`() {
        assertEquals(emptyList<VoiceGuide.Run>(), seg(""))
    }

    @Test
    fun `arabic presentation forms count as arabic`() {
        // OSM names carry these; a range test that missed them would hand an
        // Arabic name to the Latin voice.
        assertTrue(VoiceGuide.isArabic('ﻰ'))
        assertTrue(VoiceGuide.isArabic('ش'))
        assertFalse(VoiceGuide.isArabic('A'))
    }
}
