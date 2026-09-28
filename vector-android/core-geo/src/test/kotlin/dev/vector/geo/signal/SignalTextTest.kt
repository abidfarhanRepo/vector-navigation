package dev.vector.geo.signal

import dev.vector.geo.LngLat
import dev.vector.geo.RouteGeometry
import dev.vector.geo.RouteIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The exact words a driver may see about a signal, and the boundary of what
 * may never be said.
 *
 * The rule being pinned in triplicate: a phase word requires a valid timing
 * basis; everything else shows the location fact or nothing; and no string in
 * the vocabulary is a countdown, because a countdown is a precision claim the
 * project refuses to make without timing it does not have.
 */
class SignalTextTest {

    private fun prediction(
        phase: Phase,
        basis: Basis,
        confidence: Double = 0.0,
    ) = SignalPrediction("n1", phase, confidence, basis)

    @Test
    fun `the location fact is a constant and is always renderable`() {
        assertEquals("Signal ahead", SignalText.LOCATION)
    }

    @Test
    fun `a valid timing basis may name the phase`() {
        assertEquals("Likely green", SignalText.phaseText(prediction(Phase.GREEN, Basis.TIMING, 0.9)))
        assertEquals("Likely red", SignalText.phaseText(prediction(Phase.RED, Basis.TIMING, 0.8)))
        assertEquals("Timing uncertain", SignalText.phaseText(prediction(Phase.BOUNDARY, Basis.TIMING, 0.3)))
    }

    @Test
    fun `no timing or stale timing never produces a phase word`() {
        assertNull(SignalText.phaseText(prediction(Phase.UNKNOWN, Basis.LOCATION)))
        assertNull(SignalText.phaseText(prediction(Phase.UNKNOWN, Basis.STALE)))
        assertNull(SignalText.phaseText(prediction(Phase.GREEN, Basis.STALE))) // stale green is not green
    }

    @Test
    fun `the vocabulary contains no countdown under any spelling`() {
        val vocab = listOf(
            SignalText.LOCATION,
            SignalText.phaseText(prediction(Phase.GREEN, Basis.TIMING))!!,
            SignalText.phaseText(prediction(Phase.RED, Basis.TIMING))!!,
            SignalText.phaseText(prediction(Phase.BOUNDARY, Basis.TIMING))!!,
        )
        for (word in vocab) {
            assertTrue(!Regex("""\b\d+\s*(s|sec|second|minute)s?\b""").containsMatchIn(word),
                "a countdown must never be in the vocabulary: '$word'")
            assertTrue(!word.contains(" in "), "'in N' is a countdown shape: '$word'")
        }
    }

    @Test
    fun `signal callouts use the location fact and a traceable source`() {
        val route = RouteGeometry.index(
            listOf(LngLat(51.5300, 25.2850), LngLat(51.5400, 25.2850)),
        )!!
        val sig = MatchedSignal(
            ref = SignalRef("n4726715867", LngLat(51.5310, 25.285001), "osm:node:n4726715867"),
            approach = Approach(90.0, 100.0),
        )
        val callouts = dev.vector.geo.Callouts.build(route, signals = listOf(sig))
        assertEquals(1, callouts.size)
        assertEquals(dev.vector.geo.Callouts.Kind.SIGNAL, callouts[0].kind)
        assertEquals(SignalText.LOCATION, callouts[0].text)
        assertEquals("signal:n4726715867@100m", callouts[0].source)
        assertEquals(100.0, callouts[0].alongM, 1.0)
    }
}