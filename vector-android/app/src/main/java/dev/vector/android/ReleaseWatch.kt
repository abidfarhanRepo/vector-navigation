package dev.vector.android

/**
 * When the app may adopt a newly-published tile release, and when it may not.
 *
 * ## The defect this closes
 *
 * AC-19 was commissioned to find out whether MapLibre's ambient cache hides a
 * rollback. It does not — the cache was measured correct, two releases coexist
 * in it without colliding, and a rollback returns to a warm one. What hides a
 * rollback is simpler and worse:
 *
 * > The app asked which release was active exactly once, at `onCreate`, and
 * > never again.
 *
 * `VectorApi.tileSet()` had a single call site (`MainActivity.kt:768`). A
 * release swapped underneath a running app was invisible not because the
 * client was misled but because it never asked. Eight measured emulator phases
 * produced two pairs of byte-identical screenshots across two real releases:
 * the screen did not change when the release did.
 *
 * Changing the *value* in `?v=` would not have fixed that. Nothing re-read it.
 *
 * ## Why this is a state machine and not four lines in `onResume`
 *
 * Asking again is easy. Asking again *safely* is the whole problem, because
 * applying a release means rebuilding the MapLibre style, and
 * [MainActivity.applyStyle]'s own KDoc documents what that costs at the wrong
 * moment: `setStyle` destroys the current style immediately and re-acquires
 * its source handles only in an async callback, while the frame loop keeps
 * running at up to 120 Hz across the gap. Writing to a source in that window
 * is the `IllegalStateException: invalid native peer` crash — and its recorded
 * cause was an ordinary act, a driver changing the theme while navigating.
 *
 * A release swap arriving from the network mid-drive would open the same
 * window, except that no one chose it and no one is expecting it.
 *
 * So adoption is deferred out of guidance. Every transition below is a method
 * on this class rather than a branch in an Activity, for three reasons:
 *
 *  * **It can be tested without a device.** There is no MapLibre, no
 *    lifecycle, no coroutine and no clock in here.
 *  * **`Apply` cannot be produced while guidance is running.** That is a
 *    property of two functions that can be enumerated, not a review of every
 *    caller forever.
 *  * **Every step names itself** ([Step.event]), so a log line is emitted by
 *    the same code that made the decision and cannot drift from it. AC-19 was
 *    slowed down precisely by a log that recorded outcomes but not decisions:
 *    "no request issued" and "no change found" looked identical from outside.
 *
 * ## What this deliberately does not do
 *
 * It never wipes a cache. The ambient cache was measured correct and partitions
 * releases by URL; discarding it on every release would cost a re-download for
 * no measured benefit. It never invents an identity: a failed check preserves
 * what is running. And it never decides *what* a release is — that is
 * [VectorApi.TileSet.token], one rule shared by the comparison and the `?v=`
 * parameter, so the thing compared is exactly the thing put in the URL.
 */
class ReleaseWatch(
    /**
     * The floor between the *starts* of two checks.
     *
     * Not a cache and not a backoff: a bound on a pathological resume loop.
     * Ordinary app-switching costs one request; a driver who backgrounds the
     * app to take a call still sees a rollback when they come back.
     *
     * Affordable only because of Commit 4. The same endpoint used to `os.walk`
     * 18,311 files and cost 0.39-0.46 s; it is now a `stat` and a small JSON
     * read, which is what makes asking more than once per process reasonable
     * at all.
     */
    private val minIntervalMs: Long = 30_000L,
) {

    /**
     * One decision, named as it will be logged.
     *
     * The names are the contract with the verification harness and with
     * whoever is reading a bug report at 2am. They are stable strings, not
     * derived from class names, so obfuscation or a rename cannot quietly
     * change what a log says.
     */
    sealed class Step(val event: String) {
        /** A check is being issued now. */
        object CheckStarted : Step("release_check_started")

        /** A check was asked for and not issued. See [ReleaseWatch.beginCheck]. */
        data class DuplicateSuppressed(val reason: String) :
            Step("release_check_duplicate_suppressed")

        /** The server answered, and the answer parsed. */
        data class CheckSucceeded(val token: String) : Step("release_check_succeeded")

        /**
         * The server did not answer, or the answer was unusable.
         *
         * **The running release is preserved.** This is the case that must
         * never turn into an adoption: a timeout is not a new release.
         */
        object CheckFailed : Step("release_check_failed")

        /** The active release is the one already running. */
        data class Unchanged(val token: String) : Step("release_unchanged")

        /** The active release differs from the running one. */
        data class ChangeDetected(val from: String, val to: String) :
            Step("release_change_detected")

        /** A change was found while guidance was running, and was recorded, not applied. */
        data class DeferredForGuidance(val from: String, val to: String) :
            Step("release_change_deferred_guidance")

        /**
         * The style may now be rebuilt onto [tileSet].
         *
         * The ONLY step that authorises a restyle, and it is unreachable while
         * guidance is running — see [ReleaseWatch.complete] and
         * [ReleaseWatch.applyPendingIfSafe], which are the only two producers.
         *
         * @param deferred true when this release was found during guidance and
         *   has been waiting for it to end. Worth logging: it is the evidence
         *   that deferral completes rather than silently dropping a release.
         */
        data class Apply(
            val from: String,
            val to: String,
            val tileSet: VectorApi.TileSet,
            val deferred: Boolean,
        ) : Step("release_change_applied_safe")
    }

    /** What the style is currently built from. Null until [seed]. */
    var current: VectorApi.TileSet? = null
        private set

    /**
     * A release found during guidance, waiting for a safe moment.
     *
     * Deliberately NOT `MainActivity.tileSet`: the style document is built
     * from that field when `applyStyle` runs, so a release parked there would
     * be adopted by the next unrelated restyle — a theme flip mid-drive — which
     * is the very thing being avoided, reached sideways.
     */
    var pending: VectorApi.TileSet? = null
        private set

    private var inFlight = false

    /**
     * When the last check STARTED, or null if none ever has.
     *
     * Null rather than a sentinel like `Long.MIN_VALUE`, which is the obvious
     * idiom and is wrong here: `nowMs - Long.MIN_VALUE` overflows to a
     * negative number, which compares as less than any interval, so the very
     * first check of the process would be suppressed as a duplicate of a check
     * that never happened. The app would then go back to asking exactly once —
     * D1 restored by its own fix, silently, with the log cheerfully reporting
     * `release_check_duplicate_suppressed` forever.
     */
    private var lastCheckStartedAtMs: Long? = null

    /**
     * Adopt the cold-start release as the baseline, without comparing.
     *
     * The first read is not a change; there is nothing to have changed from.
     * Seeding also closes an ordering hazard: `onResume` runs before
     * `getMapAsync`'s callback, so the very first resume can arrive while the
     * cold-start read is still in flight. Until this is called, [complete]
     * treats a result as a baseline rather than as a change, so the two cannot
     * race into a spurious restyle.
     */
    fun seed(tileSet: VectorApi.TileSet) {
        current = tileSet
        pending = null
    }

    /**
     * Ask whether a check may be issued now.
     *
     * Returns [Step.CheckStarted] — and marks a check in flight — or
     * [Step.DuplicateSuppressed] with the reason. A suppressed check is
     * logged rather than skipped silently, because "did not ask" and "asked
     * and found nothing" are different facts and AC-19 lost time to a log that
     * could not tell them apart.
     *
     * The caller MUST pair a [Step.CheckStarted] with a [complete], including
     * on the failure path, or the in-flight guard stays set and no further
     * check is ever issued.
     */
    fun beginCheck(nowMs: Long): Step {
        if (inFlight) return Step.DuplicateSuppressed("in flight")
        val last = lastCheckStartedAtMs
        if (last != null) {
            val since = nowMs - last
            // `since < 0` means the clock moved backwards under us. Allowing
            // the check is the safe direction: suppressing on a negative delta
            // would silence the watch until the clock caught up, which on a
            // wall clock correction could be hours. The caller passes a
            // monotonic clock precisely so this stays unreachable — this is
            // the belt to that pair of braces.
            if (since in 0 until minIntervalMs) {
                return Step.DuplicateSuppressed("checked $since ms ago")
            }
        }
        inFlight = true
        lastCheckStartedAtMs = nowMs
        return Step.CheckStarted
    }

    /**
     * Record the outcome of a check.
     *
     * @param result what the server said, or **null** if the request failed,
     *   timed out, or could not be parsed. Null preserves the running release
     *   and records nothing pending: a failed check is not a release change,
     *   and converting one into the other is the specific fallback this design
     *   forbids.
     * @param guiding whether the app is under active guidance right now —
     *   `ui.phase == Phase.NAVIGATING`, which covers driving *and* walking
     *   (`beginWalk` sets the same phase). While true, no [Step.Apply] can be
     *   produced by this function.
     *
     * @return every step this outcome implies, in the order they happened, so
     *   the caller logs a decision trail rather than a verdict.
     */
    fun complete(result: VectorApi.TileSet?, guiding: Boolean): List<Step> {
        inFlight = false

        if (result == null) return listOf(Step.CheckFailed)

        val steps = mutableListOf<Step>(Step.CheckSucceeded(result.token))

        val running = current
        if (running == null) {
            // No baseline yet: the first answer IS the baseline. Reached only
            // if a resume check beats the cold-start read home.
            current = result
            return steps
        }

        if (result.token == running.token) {
            steps += Step.Unchanged(result.token)
            // Anything pending is now void, whatever it was: the server has
            // just said the active release is the one already running, so
            // there is nothing left to adopt.
            //
            // This is not a corner case, it is a ROLLBACK seen from the
            // client. A swap to B during a drive followed by a rollback to A
            // before it ends leaves B parked and A active. Keeping B would
            // restyle, at the end of the journey, onto the release the
            // operator has just rolled back FROM — turning a rollback into a
            // delayed roll-forward, which is worse than not noticing it.
            pending = null
            return steps
        }

        steps += Step.ChangeDetected(running.token, result.token)

        if (guiding) {
            pending = result
            steps += Step.DeferredForGuidance(running.token, result.token)
            return steps
        }

        steps += Step.Apply(running.token, result.token, result, deferred = false)
        current = result
        pending = null
        return steps
    }

    /**
     * Apply a deferred release if there is one and it is now safe.
     *
     * Called at the safe transition point after guidance ends — `endJourney`,
     * the single funnel both exits take — and again on resume. The second is
     * redundant today: `endJourney` is the only path out of
     * `Phase.NAVIGATING`, verified by enumerating every `phase` assignment in
     * `MainActivity`. It is here so that correctness does not depend on that
     * staying true. A future fourth exit would delay adoption to the next
     * resume rather than stranding the release forever.
     *
     * Returns an empty list when there is nothing pending or guidance is still
     * running, so it is safe to call often and on any thread the caller
     * already owns.
     */
    fun applyPendingIfSafe(guiding: Boolean): List<Step> {
        val waiting = pending ?: return emptyList()
        if (guiding) return emptyList()
        val running = current
        if (running != null && waiting.token == running.token) {
            pending = null
            return emptyList()
        }
        pending = null
        current = waiting
        return listOf(
            Step.Apply(running?.token ?: "", waiting.token, waiting, deferred = true))
    }

    /** Test seam: whether a check is outstanding. */
    fun isChecking(): Boolean = inFlight
}
