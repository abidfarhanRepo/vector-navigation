package dev.vector.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the app may adopt a new tile release — and, far more importantly, when
 * it may not.
 *
 * ## What is being protected
 *
 * Two failures, pulling in opposite directions.
 *
 * The first is AC-19's D1: the app asked which release was active exactly once,
 * at `onCreate`, and never again, so a rollback was invisible in-session. Eight
 * measured emulator phases produced two pairs of byte-identical screenshots
 * across two genuinely different releases. The tests below that assert a change
 * IS noticed exist because of that.
 *
 * The second is the crash `MainActivity.applyStyle`'s KDoc documents:
 * `setStyle` destroys the current style immediately and re-acquires its source
 * handles only in an async callback, with the frame loop running at up to
 * 120 Hz across the gap. `IllegalStateException: invalid native peer`, and its
 * recorded cause was an ordinary act — a driver changing the theme while
 * navigating. A release arriving from the network mid-drive would open the same
 * window, except that nobody chose it. The tests below that assert a change is
 * NOT applied exist because of that.
 *
 * Fixing the first one carelessly causes the second. That is the whole reason
 * this logic is a testable object rather than four lines in `onResume`.
 */
class ReleaseWatchTest {

    private fun ts(
        release: String,
        epoch: Long = 0L,
        minZoom: Int = 11,
        maxZoom: Int = 15,
    ) = VectorApi.TileSet(epoch, minZoom, maxZoom, release)

    private val A = ts("vector-tiles-2026-09-19T2004Z-4d627cd")
    private val B = ts("vector-tiles-2026-09-19T2104Z-9757c54")

    /** Run a check to completion, returning every step it produced. */
    private fun check(
        w: ReleaseWatch,
        result: VectorApi.TileSet?,
        guiding: Boolean,
        atMs: Long,
    ): List<ReleaseWatch.Step> {
        val begun = w.beginCheck(atMs)
        if (begun !is ReleaseWatch.Step.CheckStarted) return listOf(begun)
        return listOf(begun) + w.complete(result, guiding)
    }

    private fun events(steps: List<ReleaseWatch.Step>) = steps.map { it.event }

    private fun applies(steps: List<ReleaseWatch.Step>) =
        steps.filterIsInstance<ReleaseWatch.Step.Apply>()

    // -- the release is unchanged -----------------------------------------

    @Test
    fun `an unchanged release on resume changes nothing`() {
        val w = ReleaseWatch()
        w.seed(A)

        val steps = check(w, A, guiding = false, atMs = 100_000)

        assertEquals(
            listOf("release_check_started", "release_check_succeeded",
                   "release_unchanged"),
            events(steps))
        assertTrue("an unchanged release must not restyle", applies(steps).isEmpty())
        assertEquals(A, w.current)
        assertNull(w.pending)
    }

    // -- the release changed, and it is safe ------------------------------

    @Test
    fun `a changed release while idle is applied immediately`() {
        val w = ReleaseWatch()
        w.seed(A)

        val steps = check(w, B, guiding = false, atMs = 100_000)

        assertEquals(
            listOf("release_check_started", "release_check_succeeded",
                   "release_change_detected", "release_change_applied_safe"),
            events(steps))
        val applied = applies(steps).single()
        assertEquals(A.token, applied.from)
        assertEquals(B.token, applied.to)
        assertFalse("not deferred: nothing was being guided", applied.deferred)
        assertEquals(B, w.current)
        assertNull(w.pending)
    }

    @Test
    fun `the applied step carries the tile set the style must be built from`() {
        /**
         * Not just the token. A new release can cover a different zoom range,
         * and the style declares that range: MapLibre only overzooms ABOVE the
         * declared maximum, so a style claiming zooms the bake does not have
         * requests tiles that 404 and draws nothing. Verified on an S24 —
         * declaring 14 against a z11-13 bake made the map black past z13, and
         * navigation sets the camera to 16.5.
         */
        val w = ReleaseWatch()
        w.seed(ts("rel-a", minZoom = 11, maxZoom = 13))

        val wider = ts("vector-tiles-2026-09-19T2104Z-9757c54", minZoom = 6, maxZoom = 15)
        val applied = applies(check(w, wider, guiding = false, atMs = 1)).single()

        assertSame(wider, applied.tileSet)
        assertEquals(6, applied.tileSet.minZoom)
        assertEquals(15, applied.tileSet.maxZoom)
    }

    // -- the release changed during guidance ------------------------------

    @Test
    fun `a changed release during active guidance is deferred, not applied`() {
        val w = ReleaseWatch()
        w.seed(A)

        val steps = check(w, B, guiding = true, atMs = 100_000)

        assertEquals(
            listOf("release_check_started", "release_check_succeeded",
                   "release_change_detected", "release_change_deferred_guidance"),
            events(steps))
        assertTrue("NO restyle may be authorised during guidance",
                   applies(steps).isEmpty())
        assertEquals("the session keeps the release it started with", A, w.current)
        assertEquals(B, w.pending)
    }

    @Test
    fun `a deferred release is applied once guidance safely ends`() {
        val w = ReleaseWatch()
        w.seed(A)
        check(w, B, guiding = true, atMs = 100_000)

        val steps = w.applyPendingIfSafe(guiding = false)

        assertEquals(listOf("release_change_applied_safe"), events(steps))
        val applied = applies(steps).single()
        assertEquals(A.token, applied.from)
        assertEquals(B.token, applied.to)
        assertTrue("logged as deferred: this is the evidence deferral completes",
                   applied.deferred)
        assertEquals(B, w.current)
        assertNull(w.pending)
    }

    @Test
    fun `a deferred release stays deferred while guidance is still running`() {
        val w = ReleaseWatch()
        w.seed(A)
        check(w, B, guiding = true, atMs = 100_000)

        assertTrue(w.applyPendingIfSafe(guiding = true).isEmpty())
        assertEquals(A, w.current)
        assertEquals(B, w.pending)
    }

    @Test
    fun `the whole guidance session stays on one release`() {
        /**
         * The decision's §7, stated as a property: for the length of one
         * journey the app is bound to the release it started with, whatever
         * arrives in the meantime. The alternative re-opens a crash window
         * this codebase has already paid for once, and §6 of the decision
         * records that a mid-navigation swap is NOT measured as safe.
         */
        val w = ReleaseWatch(minIntervalMs = 0L)
        w.seed(A)

        val C = ts("vector-tiles-2026-09-19T2204Z-abcdef1")
        var t = 0L
        repeat(20) {
            t += 60_000
            val steps = check(w, if (it % 2 == 0) B else C, guiding = true, atMs = t)
            assertTrue("no restyle at any point during guidance",
                       applies(steps).isEmpty())
            assertEquals(A, w.current)
        }

        // One apply when it ends, for the most recent release seen.
        val steps = w.applyPendingIfSafe(guiding = false)
        assertEquals(1, applies(steps).size)
        assertEquals(C.token, applies(steps).single().to)
    }

    @Test
    fun `a release that changes and changes back during guidance is not applied`() {
        /**
         * A swap away and back is what a rollback looks like from the client,
         * and at the end of it the app is running exactly the release that is
         * active. Restyling to reach the state it is already in would be a
         * gratuitous style rebuild — the operation this whole class exists to
         * ration.
         */
        val w = ReleaseWatch(minIntervalMs = 0L)
        w.seed(A)

        check(w, B, guiding = true, atMs = 10_000)
        assertEquals(B, w.pending)
        check(w, A, guiding = true, atMs = 20_000)

        assertTrue(w.applyPendingIfSafe(guiding = false).isEmpty())
        assertEquals(A, w.current)
        assertNull(w.pending)
    }

    // -- no restyle during guidance, exhaustively -------------------------

    @Test
    fun `no sequence of events can authorise a restyle while guidance runs`() {
        /**
         * The property, rather than an example of it. Every interleaving of
         * (check outcome x guidance state) up to depth four, asserting that no
         * step produced while `guiding` is true is ever an Apply.
         *
         * Enumerating beats reviewing: `Apply` has exactly two producers, and
         * this covers both under every sequence they can be reached by.
         */
        val outcomes = listOf(null, A, B, ts("vector-tiles-2026-09-19T2204Z-abcdef1"))
        var checked = 0

        fun walk(w: ReleaseWatch, depth: Int, t: Long) {
            if (depth == 0) return
            for (outcome in outcomes) {
                for (guiding in listOf(true, false)) {
                    val fresh = ReleaseWatch(minIntervalMs = 0L)
                    fresh.seed(w.current ?: A)
                    val steps = check(fresh, outcome, guiding, t) +
                        fresh.applyPendingIfSafe(guiding)
                    checked++
                    if (guiding) {
                        assertTrue(
                            "a restyle was authorised while guiding: " +
                                events(steps),
                            applies(steps).isEmpty())
                    }
                    walk(fresh, depth - 1, t + 60_000)
                }
            }
        }

        walk(ReleaseWatch(minIntervalMs = 0L).also { it.seed(A) }, 4, 1_000)
        assertTrue("the enumeration must actually have run", checked > 500)
    }

    // -- the check failed --------------------------------------------------

    @Test
    fun `a failed release check preserves the current release`() {
        val w = ReleaseWatch()
        w.seed(A)

        val steps = check(w, null, guiding = false, atMs = 100_000)

        assertEquals(listOf("release_check_started", "release_check_failed"),
                     events(steps))
        assertTrue(applies(steps).isEmpty())
        assertEquals("a timeout is not a release change", A, w.current)
        assertNull("and it records nothing to adopt later", w.pending)
    }

    @Test
    fun `a failed check does not disturb a release already deferred`() {
        val w = ReleaseWatch(minIntervalMs = 0L)
        w.seed(A)
        check(w, B, guiding = true, atMs = 10_000)

        check(w, null, guiding = true, atMs = 20_000)

        assertEquals("the deferred release survives a blip", B, w.pending)
        assertEquals(B, w.applyPendingIfSafe(guiding = false)
            .filterIsInstance<ReleaseWatch.Step.Apply>().single().tileSet)
    }

    @Test
    fun `a failing check never becomes a release change however often it fails`() {
        val w = ReleaseWatch(minIntervalMs = 0L)
        w.seed(A)

        var t = 0L
        repeat(50) {
            t += 60_000
            val steps = check(w, null, guiding = false, atMs = t)
            assertTrue(applies(steps).isEmpty())
        }
        assertEquals(A, w.current)
    }

    // -- duplicate suppression --------------------------------------------

    @Test
    fun `repeated resume events do not trigger duplicate style rebuilds`() {
        val w = ReleaseWatch(minIntervalMs = 30_000L)
        w.seed(A)

        // Five resumes inside the interval; the server has moved to B.
        val all = mutableListOf<ReleaseWatch.Step>()
        repeat(5) { all += check(w, B, guiding = false, atMs = 100_000L + it * 1_000L) }

        assertEquals("exactly one restyle for five resumes", 1, applies(all).size)
        assertEquals(4, all.count { it.event == "release_check_duplicate_suppressed" })
        assertEquals(B, w.current)
    }

    @Test
    fun `a check in flight suppresses the next one`() {
        /**
         * Distinct from the interval: this is what stops two resumes in quick
         * succession — a dismissed permission dialog, a multi-window focus
         * change — issuing two requests that race each other's results.
         */
        val w = ReleaseWatch(minIntervalMs = 0L)
        w.seed(A)

        assertTrue(w.beginCheck(1_000) is ReleaseWatch.Step.CheckStarted)
        assertTrue(w.isChecking())
        val second = w.beginCheck(2_000)
        assertTrue(second is ReleaseWatch.Step.DuplicateSuppressed)
        assertEquals("in flight",
                     (second as ReleaseWatch.Step.DuplicateSuppressed).reason)

        w.complete(A, guiding = false)
        assertFalse(w.isChecking())
        assertTrue(w.beginCheck(3_000) is ReleaseWatch.Step.CheckStarted)
    }

    @Test
    fun `a failed check releases the in-flight guard`() {
        /**
         * The guard is cleared by `complete`, including on the failure path.
         * If it were not, one timeout would silence every future check for the
         * life of the process — the exact defect being fixed, recreated by the
         * fix.
         */
        val w = ReleaseWatch(minIntervalMs = 0L)
        w.seed(A)

        w.beginCheck(1_000)
        w.complete(null, guiding = false)

        assertFalse(w.isChecking())
        assertEquals(1, applies(check(w, B, guiding = false, atMs = 2_000)).size)
    }

    @Test
    fun `a suppressed check is logged rather than passing silently`() {
        /**
         * AC-19 lost time to a log in which "did not ask" and "asked and found
         * nothing" looked identical. They are different facts and the trail
         * has to carry both.
         */
        val w = ReleaseWatch(minIntervalMs = 30_000L)
        w.seed(A)
        w.beginCheck(100_000)
        w.complete(A, guiding = false)

        val step = w.beginCheck(101_000)
        assertEquals("release_check_duplicate_suppressed", step.event)
    }

    @Test
    fun `the interval measures from the start of the last check, not the end`() {
        val w = ReleaseWatch(minIntervalMs = 30_000L)
        w.seed(A)
        w.beginCheck(100_000)
        w.complete(A, guiding = false)

        assertTrue(w.beginCheck(129_999) is ReleaseWatch.Step.DuplicateSuppressed)
        assertTrue(w.beginCheck(130_000) is ReleaseWatch.Step.CheckStarted)
    }

    // -- cold start --------------------------------------------------------

    @Test
    fun `a cold start selects the active release without calling it a change`() {
        val w = ReleaseWatch()
        w.seed(B)

        assertEquals(B, w.current)
        assertNull(w.pending)

        val steps = check(w, B, guiding = false, atMs = 100_000)
        assertTrue("the first read is a baseline, not a change",
                   applies(steps).isEmpty())
    }

    @Test
    fun `a resume check that beats the cold start home becomes the baseline`() {
        /**
         * `onResume` runs BEFORE `getMapAsync`'s callback completes, so the
         * first resume check can return while the cold-start read is still in
         * flight. With no baseline to compare against, the answer IS the
         * baseline — which is what stops the race producing a restyle nobody
         * asked for on the first frame.
         */
        val w = ReleaseWatch()  // never seeded

        val steps = check(w, B, guiding = false, atMs = 1_000)

        assertEquals(listOf("release_check_started", "release_check_succeeded"),
                     events(steps))
        assertTrue(applies(steps).isEmpty())
        assertEquals(B, w.current)
    }

    // -- the epoch contract ------------------------------------------------

    @Test
    fun `legacy epoch behaviour remains compatible`() {
        /**
         * A server that reports no release — one predating the contract, or
         * Commit 4 running on the un-migrated volume production has today —
         * is compared by its epoch, which is exactly what this client did
         * before V7.7. `?v=` is built from the same rule, so the thing
         * compared is the thing written into the URL.
         */
        val w = ReleaseWatch(minIntervalMs = 0L)
        val old = VectorApi.TileSet(1789000000L, 11, 13, "")
        w.seed(old)

        assertEquals("1789000000", old.token)

        val same = VectorApi.TileSet(1789000000L, 11, 13, "")
        assertTrue(applies(check(w, same, guiding = false, atMs = 1_000)).isEmpty())

        val rebaked = VectorApi.TileSet(1789003600L, 11, 13, "")
        val applied = applies(check(w, rebaked, guiding = false, atMs = 2_000)).single()
        assertEquals("1789000000", applied.from)
        assertEquals("1789003600", applied.to)
    }

    @Test
    fun `a server that starts naming releases is a change, once`() {
        /**
         * The migration moment: the same tiles, now behind a release id. The
         * token genuinely changes, so the app restyles once and then settles.
         * One extra style rebuild at the moment production migrates is the
         * honest cost of the contract, and it happens while nobody is being
         * guided or it does not happen at all.
         */
        val w = ReleaseWatch(minIntervalMs = 0L)
        w.seed(VectorApi.TileSet(1789000000L, 11, 15, ""))

        val named = VectorApi.TileSet(1789000000L, 11, 15,
                                      "vector-tiles-2026-09-19T2004Z-4d627cd")
        assertEquals(1, applies(check(w, named, guiding = false, atMs = 1_000)).size)
        assertTrue(applies(check(w, named, guiding = false, atMs = 2_000)).isEmpty())
    }
}
