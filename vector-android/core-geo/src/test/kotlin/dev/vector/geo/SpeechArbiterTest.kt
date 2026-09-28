package dev.vector.geo

import dev.vector.geo.SpeechArbiter.Decision
import dev.vector.geo.SpeechArbiter.Kind
import dev.vector.geo.SpeechArbiter.Utterance
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a new sentence does to the one already playing, and to the one waiting.
 *
 * Every sentence used to flush the engine. The 2026-09-24 drive heard an
 * over-limit alert cut off mid-word by the camera alert; the S24 Ultra drive
 * run of the first fix measured 13 guidance-over-guidance cuts across eleven
 * drives. The last three tests replay those measured sequences through a tiny
 * model of the engine. See [SpeechArbiter].
 */
class SpeechArbiterTest {

    private fun courtesy(s: String) = Utterance(s, Kind.COURTESY)
    private fun prepare(s: String) = Utterance(s, Kind.PREPARE)
    private fun now(s: String) = Utterance(s, Kind.NOW)
    private fun alert(s: String) = Utterance(s, Kind.ALERT)

    private fun decide(playing: Utterance?, pending: Utterance?, next: Utterance) =
        SpeechArbiter.decide(playing, pending, next)

    // ---- the rules, one at a time -------------------------------------------

    @Test
    fun `a silent engine speaks whatever arrives`() {
        for (u in listOf(courtesy("Route updated"), prepare("In 300 metres, turn left"),
                         now("Turn left"), alert("Speed limit 60"))) {
            assertEquals(Decision.Interrupt(u, null), decide(null, null, u))
        }
    }

    @Test
    fun `NOW interrupts a playing PREPARE - the countdown is stale`() {
        val n = now("Turn left onto Al Sadd Street")
        assertEquals(Decision.Interrupt(n, null), decide(prepare("In 150 metres, keep right"), null, n))
    }

    @Test
    fun `NOW interrupts a playing ALERT`() {
        val n = now("Turn right onto Al Amir Street")
        assertEquals(Decision.Interrupt(n, null), decide(alert("Speed camera ahead"), null, n))
    }

    @Test
    fun `NOW does not interrupt a playing NOW - it waits`() {
        val n = now("Bear right to stay on Al Waab Street")
        assertEquals(Decision.Queue(n), decide(now("Turn left onto Al Sadd Street"), null, n))
    }

    @Test
    fun `PREPARE never interrupts anything`() {
        val p = prepare("In 150 metres, continue on the slip road")
        for (playing in listOf(courtesy("Route updated"), prepare("In 400 metres, keep left"),
                               now("Continue on Qalat Al Askar Street"), alert("Speed limit 60"))) {
            assertEquals(Decision.Queue(p), decide(playing, null, p))
        }
    }

    @Test
    fun `an alert never interrupts anything`() {
        // 0:17 on the 2026-09-24 drive: "Speed limit 50" cut off by the camera.
        val a = alert("Speed camera ahead")
        for (playing in listOf(courtesy("Starting navigation"), alert("Speed limit 50"),
                               prepare("In 300 metres, turn left"), now("Turn left"))) {
            assertEquals(Decision.Queue(a), decide(playing, null, a))
        }
    }

    @Test
    fun `COURTESY is not interrupted, even by NOW`() {
        // The deliberate departure documented on SpeechArbiter: letting it
        // run costs under a second, cutting it is "Starting navi—".
        val n = now("Continue on Qalat Al Askar Street")
        assertEquals(Decision.Queue(n), decide(courtesy("Starting navigation"), null, n))
    }

    @Test
    fun `a newer guidance sentence replaces the one waiting`() {
        val n = now("Continue on the slip road")
        assertEquals(Decision.Queue(n),
            decide(now("Continue on Qalat Al Askar Street"),
                   prepare("In 150 metres, continue on the slip road"), n))
    }

    @Test
    fun `guidance replaces a waiting alert`() {
        val p = prepare("In 300 metres, turn left")
        assertEquals(Decision.Queue(p), decide(now("Turn right"), alert("Speed limit 60"), p))
    }

    @Test
    fun `an alert does not displace waiting guidance - it is dropped`() {
        assertEquals(Decision.Drop,
            decide(now("Turn right"), prepare("In 300 metres, turn left"), alert("Speed limit 60")))
    }

    @Test
    fun `a newer alert replaces a waiting alert`() {
        val a = alert("Speed limit 40")
        assertEquals(Decision.Queue(a), decide(now("Turn right"), alert("Speed limit 60"), a))
    }

    @Test
    fun `a courtesy line does not displace anything waiting`() {
        assertEquals(Decision.Drop,
            decide(now("Turn right"), alert("Speed limit 60"), courtesy("Route updated")))
    }

    @Test
    fun `an interrupting NOW takes waiting guidance with it but keeps a waiting alert`() {
        val n = now("Turn left")
        assertEquals(Decision.Interrupt(n, null),
            decide(prepare("In 150 metres, turn left"), prepare("In 300 metres, keep right"), n))
        val waiting = alert("Speed camera ahead")
        assertEquals(Decision.Interrupt(n, waiting),
            decide(prepare("In 150 metres, turn left"), waiting, n))
    }

    @Test
    fun `the TURN sentence still playing is not restarted by the NOW stage`() {
        val s = "Slight left onto Al Mirqab Al Jadeed Street"
        assertEquals(Decision.Drop, decide(now(s), null, now(s)))
    }

    @Test
    fun `a sentence already waiting is not offered twice`() {
        val s = "Bear right to stay on Ahmed Bin Mohammed Bin Thani Street"
        assertEquals(Decision.Drop, decide(now("At the roundabout, take the third exit"), now(s), now(s)))
    }

    @Test
    fun `a sentence already contained in what is playing is dropped`() {
        assertEquals(Decision.Drop,
            decide(now("Turn left onto X, then turn right"), null, now("Turn left onto X")))
    }

    @Test
    fun `an extension of what is playing waits as only the new part`() {
        assertEquals(
            Decision.Queue(Utterance("then turn right", Kind.NOW, heard = "Turn left onto X, then turn right")),
            decide(now("Turn left onto X"), null, now("Turn left onto X, then turn right")),
        )
    }

    @Test
    fun `a street name that merely starts the same is a different sentence`() {
        val n = now("Turn left onto Al Saddiq Street")
        assertEquals(Decision.Queue(n), decide(now("Turn left onto Al Sadd"), null, n))
    }

    // ---- the measured S24 sequences, replayed -------------------------------

    /**
     * A minimal engine: what is playing, what waits, and a transcript of every
     * sentence as it was actually heard — `(cut)` marking one stopped
     * mid-sentence. [finish] is the engine's onDone.
     */
    private class Engine {
        var playing: Utterance? = null
        var pending: Utterance? = null
        val heard = mutableListOf<String>()

        fun say(u: Utterance) {
            when (val d = SpeechArbiter.decide(playing, pending, u)) {
                is Decision.Interrupt -> {
                    playing?.let { heard[heard.lastIndex] = it.text + " (cut)" }
                    start(d.now)
                    pending = d.pending
                }
                is Decision.Queue -> pending = d.pending
                Decision.Drop -> Unit
            }
        }

        fun finish() {
            playing = null
            val p = pending ?: return
            pending = null
            start(p)
        }

        private fun start(u: Utterance) {
            playing = u
            heard += u.text
        }

        fun drain() { while (playing != null) finish() }
    }

    @Test
    fun `drive E - the three-way chain plays whole, the stale countdown is never heard`() {
        // Before: Qalat cut at 1.0 s, the 150 m line cut at 0.7 s.
        val e = Engine()
        e.say(now("Continue on Qalat Al Askar Street"))
        e.say(prepare("In 150 metres, continue on the slip road"))   // t+1.0
        e.say(now("Continue on the slip road"))                      // t+1.7
        e.drain()
        assertEquals(listOf("Continue on Qalat Al Askar Street", "Continue on the slip road"), e.heard)
    }

    @Test
    fun `drive F - the roundabout sentence is not cut by the bear-right that follows it`() {
        // Before: cut at 2.8 s; the repeat 3.2 s later was then dropped.
        val f = Engine()
        val roundabout = "At the roundabout, take the third exit onto Ahmed Bin Mohammed Bin Thani Street, then bear right"
        val bear = "Bear right to stay on Ahmed Bin Mohammed Bin Thani Street"
        f.say(now(roundabout))
        f.say(now(bear))
        f.say(now(bear))
        f.drain()
        assertEquals(listOf(roundabout, bear), f.heard)
    }

    @Test
    fun `starting navigation is heard whole and the first instruction follows it`() {
        // Before: every "Starting navigation" cut ~0.7 s in.
        val s = Engine()
        s.say(courtesy("Starting navigation"))
        s.say(now("Continue on Qalat Al Askar Street"))              // t+0.74
        s.drain()
        assertEquals(listOf("Starting navigation", "Continue on Qalat Al Askar Street"), s.heard)
    }

    @Test
    fun `drive I - the service-road line waits for the slip-road line`() {
        val i = Engine()
        i.say(now("Continue on the slip road, then continue"))
        i.say(now("Continue on the service road"))                  // t+2.6
        i.drain()
        assertEquals(listOf("Continue on the slip road, then continue", "Continue on the service road"), i.heard)
    }

    @Test
    fun `a countdown still playing when the maneuver arrives is cut for it`() {
        // The one interrupt the rules keep.
        val x = Engine()
        x.say(prepare("In 150 metres, turn left onto Al Sadd Street"))
        x.say(now("Turn left onto Al Sadd Street"))
        x.drain()
        assertEquals(
            listOf("In 150 metres, turn left onto Al Sadd Street (cut)", "Turn left onto Al Sadd Street"),
            x.heard,
        )
    }
}
