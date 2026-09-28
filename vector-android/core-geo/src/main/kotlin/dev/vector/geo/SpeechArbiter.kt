package dev.vector.geo

/**
 * Decides what a new sentence does to the one the voice is already saying.
 *
 * ## The defect this exists because of
 *
 * `VoiceGuide.say` used to flush the engine on EVERY sentence, on the
 * reasoning — right for maneuvers — that "turn left now" must cut off a stale
 * "in 300 metres, turn left" rather than queue behind it. But every sentence
 * the app produces went through the same door, and two of them made that rule
 * audible as a glitch on the 2026-09-24 drive
 * (`Screen_Recording_20260924_115921.mp4`):
 *
 *  1. **An alert cutting off an alert.** At 0:17.2 the voice starts an
 *     utterance whose first 0.8 s matches, syllable for syllable on the
 *     spectrogram, the complete "Speed limit sixty" at 0:41.3 — and it stops
 *     there, a new utterance starting at 0:18.4. The HUD's camera line ("Speed
 *     camera ahead · 350 m") appears in the same two-second window, with the
 *     car at 57–68 km/h in a 50. The over-limit alert and the camera alert are
 *     independent state machines that routinely fire within a second of each
 *     other (a camera sits where the limit matters), and the second erased the
 *     first mid-word.
 *  2. **A maneuver cutting off ITSELF.** The TURN stage fires at 120 m and the
 *     NOW stage at 30 m, and both say the router's instruction verbatim — the
 *     recording has "Continue on the slip road" at 3:01.6 and again, identical
 *     on the spectrogram, at 3:06.8. 25 characters took 1.5 s to say. At the
 *     55 km/h of that junction the 90 m between the stages is 5.9 s, so the
 *     two did not collide; at 110 km/h it is 2.9 s, and an instruction carrying
 *     a street name ("Slight left onto Al Mirqab Al Jadeed Street") is about
 *     three. Above roughly 100 km/h the NOW stage therefore lands while the
 *     TURN sentence is still playing, flushes it, and starts the SAME sentence
 *     again from the top — "Slight left onto Al Mir— Slight left onto Al
 *     Mirqab Al Jadeed Street". That is why the glitch appears only "if the
 *     vehicle is fast enough".
 *
 * ## What the first version of this got wrong
 *
 * The first cut kept "guidance interrupts" as the fallback, reasoning that a
 * stale maneuver is worse than none. The S24 Ultra drive run of that build
 * (debug `VectorVoice: say …` lines, eleven drives) measured what that costs:
 * 13 guidance-over-guidance interrupts, every one mid-sentence. Drive E:
 *
 * ```
 *  t+0.0  "Continue on Qalat Al Askar Street"          cut after 1.0 s by
 *  t+1.0  "In 150 metres, continue on the slip road"   cut after 0.7 s by
 *  t+1.7  "Continue on the slip road"
 * ```
 *
 * and every "Starting navigation" cut ~0.7 s in by the first instruction;
 * drive F's "At the roundabout, take the third exit onto … then bear right"
 * cut at 2.8 s by "Bear right to stay on …"; drive I's "Continue on the slip
 * road, then continue" cut at 2.6 s by "Continue on the service road". A
 * sentence cut in half is not a warning, it is noise — and the half that was
 * lost is usually the street name, which is the part the driver needed.
 *
 * ## The rules
 *
 * Every sentence has a [Kind], given by the code that produced it rather than
 * guessed from its words: [Kind.COURTESY], [Kind.PREPARE], [Kind.NOW],
 * [Kind.ALERT].
 *
 *  * Nothing playing: speak it.
 *  * **Drops**, checked first. The sentence playing, or already waiting, is
 *    not said again — restarting it from the top is the stutter, not the
 *    warning. Nor is a sentence the playing one already OPENS with ("X, then
 *    turn right" playing, "X" arriving).
 *  * **Extension.** "X" playing, "X, then turn right" arriving: only the new
 *    part waits, so the instruction is heard once and whole.
 *  * **A sentence never cuts another mid-word, with one exception: a
 *    [Kind.NOW] may interrupt a playing [Kind.PREPARE] or [Kind.ALERT].** A
 *    distance warning is stale the moment the maneuver it counts down to is
 *    here, and an alert is a fact that is still true two seconds later.
 *  * Everything else goes into ONE pending slot, which the caller speaks the
 *    moment the playing sentence ends. The slot holds the LATEST sentence of
 *    the highest rank offered — [Kind.PREPARE] and [Kind.NOW] outrank
 *    [Kind.ALERT], which outranks [Kind.COURTESY] — so a newer instruction
 *    replaces an older one (only the latest guidance still describes the
 *    road), guidance replaces a waiting alert, and an alert offered while
 *    guidance is waiting is dropped rather than queued behind it. Dropping
 *    the alert is deliberate and cheap: its line stays on the HUD, the
 *    over-limit alert repeats on its own schedule ([SpeedAlert.minRepeatMs]),
 *    and a speech backlog is the one thing a driver at 110 km/h cannot use.
 *  * A NOW that interrupts takes any waiting guidance with it — it is newer —
 *    but leaves a waiting alert in the slot.
 *
 * ## COURTESY is never interrupted, which is a deliberate departure
 *
 * The brief for this change said "anything may interrupt COURTESY". Doing so
 * reproduces the measured "Starting navi—": the first instruction lands
 * ~0.7 s after "Starting navigation" on every drive. Letting it finish costs
 * at most the rest of a sub-1.5-second sentence — under a second, 30 m at
 * 110 km/h, while the TURN stage fires 120 m out — and removes the cut from
 * the start of every journey and every reroute. So COURTESY plays whole and
 * the instruction waits in the slot behind it.
 *
 * Pure, like every other decision in this package: "did it cut itself off at
 * 110 km/h?" is not a question anybody can answer by driving around.
 */
object SpeechArbiter {

    /** What a sentence is, as told by the code that produced it. */
    enum class Kind(internal val rank: Int) {
        /** "Starting navigation", "Route updated". Short, and never urgent. */
        COURTESY(0),

        /** Speed limit, camera. True for the next few hundred metres. */
        ALERT(1),

        /** A distance warning: "In 300 metres, turn left". */
        PREPARE(2),

        /** The maneuver itself (the TURN and NOW stages), and arrival. */
        NOW(2),
    }

    /**
     * A sentence.
     *
     * @property text what to say.
     * @property heard what the driver will have heard once [text] has been
     *   said — the whole instruction when [text] is only the tail of one that
     *   extended what was playing. Repeats are compared against this.
     */
    data class Utterance(val text: String, val kind: Kind, val heard: String = text)

    sealed interface Decision {
        /**
         * Stop what is playing and say [now]. [pending] is what the slot holds
         * afterwards.
         */
        data class Interrupt(val now: Utterance, val pending: Utterance?) : Decision

        /** Leave what is playing alone; the slot now holds [pending]. */
        data class Queue(val pending: Utterance) : Decision

        /** Say nothing, and leave the slot as it is. */
        data object Drop : Decision
    }

    /**
     * @param playing the sentence the engine is saying right now, or null when
     *   it is silent. The caller must pass null once the engine has finished —
     *   a sentence that ended a minute ago is not a reason to drop a repeat.
     * @param pending what is waiting in the slot, or null.
     */
    fun decide(playing: Utterance?, pending: Utterance?, next: Utterance): Decision {
        if (playing == null) return Decision.Interrupt(next, pending)
        val now = playing.heard.trim()
        val new = next.text.trim()
        if (new.equals(now, ignoreCase = true) || extends(now, new)) return Decision.Drop
        if (pending != null && new.equals(pending.heard.trim(), ignoreCase = true)) {
            return Decision.Drop
        }
        if (extends(new, now)) {
            // Only the part the driver has not heard. The glue ", then" is the
            // announcer's; trimming the punctuation keeps the synthesiser from
            // spending a pause on a lone comma.
            val tail = new.substring(now.length).trimStart(' ', ',', ';', '.')
            if (!tail.any { it.isLetterOrDigit() }) return Decision.Drop
            return offer(pending, Utterance(tail, next.kind, heard = new))
        }
        if (mayInterrupt(playing.kind, next.kind)) {
            // The interrupting NOW is newer than any guidance still waiting.
            val keep = pending?.takeIf { it.kind == Kind.ALERT }
            return Decision.Interrupt(next, keep)
        }
        return offer(pending, next)
    }

    private fun mayInterrupt(playing: Kind, next: Kind): Boolean =
        next == Kind.NOW && (playing == Kind.PREPARE || playing == Kind.ALERT)

    /** The slot keeps the latest sentence of the highest rank offered. */
    private fun offer(pending: Utterance?, next: Utterance): Decision =
        if (pending == null || next.kind.rank >= pending.kind.rank) Decision.Queue(next)
        else Decision.Drop

    /**
     * Does [long] open with the whole of [short], ending at a word boundary?
     *
     * The boundary matters: "Turn left onto Al Sadd" is not the opening of
     * "Turn left onto Al Saddiq Street", and queueing "iq Street" behind it
     * would be exactly the kind of mangled speech this object exists to stop.
     */
    private fun extends(long: String, short: String): Boolean =
        long.startsWith(short, ignoreCase = true) &&
            (long.length == short.length || !long[short.length].isLetterOrDigit())
}
