package dev.vector.geo

import kotlin.math.abs

/**
 * Tracks where the vehicle is on a route, and interpolates between GPS fixes.
 *
 * This is the piece that decides how navigation FEELS, and it is deliberately a
 * plain state machine with no Android and no rendering in it, so its behaviour
 * can be asserted exhaustively on the JVM.
 *
 * The problem it solves: GPS arrives at ~1 Hz. Drawing the vehicle only when a
 * fix lands gives one lurch per second. The web client's answer was to animate
 * the camera toward each fix over 600 ms, which measured at ~13 fps and ~59 ms
 * of CPU per fix, of which ~95% was map re-render caused by overlapping
 * animations that each interrupted the last.
 *
 * The answer here is the one production navigators use: hold a position ALONG
 * the route, advance it every frame at the current speed, and use each fix to
 * CORRECT it rather than to set it. Motion is then continuous at display rate
 * and costs one cheap camera write per frame instead of an animation engine.
 *
 * Snapping is conditional, never unconditional: past [snapMaxM] the vehicle is
 * not on the route we drew, and pretending otherwise would draw the puck on a
 * road the driver has left. There the tracker reports [State.OffRoute] and the
 * caller should show the raw fix and reroute.
 */
class RouteTracker(
    private val snapMaxM: Double = 40.0,
    /**
     * Beyond this the driver has left the road. Public because [NavSession]
     * compares it against the ROUTE'S OWN endpoint snap to decide whether a
     * reroute could possibly help — two copies of this number would be two
     * places to get that wrong.
     */
    val offRouteM: Double = 60.0,
    private val reckonMaxS: Double = 3.0,
    private val correctGain: Double = 0.25,
    /**
     * How far BACK along the route a locked projection may move in one fix.
     *
     * Wide enough for GPS error and for a car reversing out of a space; too
     * narrow to reach the opposite carriageway of a section the route has
     * already used. See [RouteIndex.project]'s KDoc for the 152 m backwards
     * jump this number exists to prevent.
     */
    private val searchBackM: Double = 60.0,
    /**
     * How far FORWARD a locked projection may move in one fix.
     *
     * Has to cover the distance a car covers between fixes at the worst
     * plausible combination of speed and dropped updates: 33 m/s for the 3 s
     * the tracker will dead-reckon before giving up is 100 m, so 250 m is
     * comfortable. Generous on purpose — being too tight forward would drop
     * the lock on a motorway, and the failure this window prevents is a
     * BACKWARD one.
     */
    private val searchFwdM: Double = 250.0,
) {
    sealed interface State {
        /** No route set, or no fix yet. */
        data object Idle : State

        /** On the route: [alongM] is authoritative, the puck follows the line. */
        data class OnRoute(
            val alongM: Double,
            val offsetM: Double,
            val position: LngLat,
            val bearing: Double,
            val speedMs: Double,
            val remainingM: Double,
        ) : State

        /** Off the route by more than [offRouteM]; the caller should reroute. */
        data class OffRoute(val offsetM: Double, val position: LngLat) : State
    }

    private var index: RouteIndex? = null
    private var alongM: Double = 0.0
    private var speedMs: Double = 0.0
    private var locked: Boolean = false
    // Nullable, NOT a 0L sentinel. 0 is a perfectly legal timestamp — the unit
    // tests use it as t=0 — and conflating "unset" with "zero" meant the frame
    // clock never started and the stale-fix check was skipped outright. Both
    // bugs were caught by the tests below and neither would have shown up in
    // production, where System.nanoTime() never returns 0.
    private var lastFixNanos: Long? = null
    private var lastFrameNanos: Long? = null

    var state: State = State.Idle
        private set

    /** True when the tracker is holding a route lock and interpolating. */
    val isLocked: Boolean get() = locked

    fun setRoute(coords: List<LngLat>) {
        index = RouteGeometry.index(coords)
        alongM = 0.0
        speedMs = 0.0
        locked = false
        lastFixNanos = null
        lastFrameNanos = null
        state = State.Idle
    }

    fun clearRoute() {
        index = null
        locked = false
        state = State.Idle
    }

    /**
     * Feed a GPS fix.
     *
     * [speedMs] is the receiver's speed when it has one. Receivers commonly omit
     * speed and bearing below walking pace, so a null is normal and must not be
     * read as "stopped" — the previous speed is retained and the dead-reckoning
     * step simply keeps using it until a fix disagrees.
     */
    fun onFix(
        fix: LngLat,
        speedMs: Double?,
        nowNanos: Long,
        /**
         * The vehicle's heading, or null.
         *
         * Passed straight through to [RouteIndex.project], which uses it ONLY
         * to choose between two passes of the same street on a first lock — see
         * its KDoc for the half-kilometre error this prevents on a route that
         * U-turns. The caller is responsible for withholding it when the
         * vehicle is not moving, because a parked car's GPS heading is close to
         * random.
         */
        headingDeg: Double? = null,
    ): State {
        val idx = index ?: run {
            state = State.Idle
            return state
        }
        // Constrained to a window around where we already are, but ONLY while
        // a lock is held: an unlocked tracker has no trustworthy along-route
        // position to constrain against, which is the first fix of a journey
        // and the re-lock after a tunnel.
        var proj = (if (locked) idx.project(fix, alongM, searchBackM, searchFwdM)
                    else idx.project(fix, null, 0.0, 0.0, headingDeg)) ?: run {
            state = State.Idle
            return state
        }
        if (locked && proj.offsetM > offRouteM) {
            // The window says we have left the route. Before believing it, ask
            // the whole route — a driver whose position genuinely jumped along
            // it (an app resumed after being backgrounded, a fix arriving after
            // a long gap that did not quite trip the stale check) is on the
            // route, just not where the window was looking. Only if BOTH agree
            // is this a deviation, so the window can never invent one.
            val global = idx.project(fix)
            if (global != null && global.offsetM <= snapMaxM) proj = global
        }
        lastFixNanos = nowNanos
        if (speedMs != null && speedMs >= 0) this.speedMs = speedMs

        if (proj.offsetM > offRouteM) {
            locked = false
            state = State.OffRoute(proj.offsetM, fix)
            return state
        }

        if (proj.offsetM <= snapMaxM) {
            if (!locked) {
                // First lock: accept the measurement outright. Easing in from an
                // arbitrary starting value would slide the puck up the road.
                alongM = proj.alongM
                locked = true
            } else {
                // Nudge, do not snap. Setting alongM to the measurement on every
                // fix reintroduces the 1 Hz lurch this class exists to remove;
                // a gain converges within a few fixes and stays smooth.
                alongM += (proj.alongM - alongM) * correctGain
            }
        } else {
            // Between snapMaxM and offRouteM: too far to trust the projection,
            // not far enough to declare a deviation. Hold the lock but do not
            // correct — a GPS excursion in an urban canyon should not drag the
            // puck sideways, and it should not trigger a reroute either.
            if (!locked) {
                state = State.OffRoute(proj.offsetM, fix)
                return state
            }
        }
        return emitOnRoute(idx)
    }

    /**
     * Advance the interpolated position. Call once per rendered frame.
     * Returns null when there is nothing to draw (idle, off-route, or stale).
     */
    fun onFrame(nowNanos: Long): State.OnRoute? {
        val idx = index ?: return null
        if (!locked) return null

        // Stop extrapolating if the fixes dried up. A tunnel or a dropout should
        // freeze the puck, not sail it confidently down the road — a dead-reckoned
        // position that keeps moving without evidence is worse than a stale one,
        // because the driver cannot tell it is wrong.
        val sinceFix = lastFixNanos?.let { (nowNanos - it) / 1e9 }
        if (sinceFix != null && sinceFix > reckonMaxS) {
            locked = false
            lastFrameNanos = null
            return null
        }

        val prev = lastFrameNanos
        val dt = if (prev == null) 0.0 else ((nowNanos - prev) / 1e9).coerceAtMost(0.25)
        lastFrameNanos = nowNanos
        alongM = (alongM + speedMs * dt).coerceIn(0.0, idx.totalM)
        return emitOnRoute(idx) as State.OnRoute
    }

    private fun emitOnRoute(idx: RouteIndex): State {
        val pt = idx.pointAt(alongM)
        if (pt == null) {
            state = State.Idle
            return state
        }
        state = State.OnRoute(
            alongM = alongM,
            offsetM = 0.0,
            position = pt.position,
            bearing = pt.bearing,
            speedMs = speedMs,
            remainingM = idx.totalM - alongM,
        )
        return state
    }
}
