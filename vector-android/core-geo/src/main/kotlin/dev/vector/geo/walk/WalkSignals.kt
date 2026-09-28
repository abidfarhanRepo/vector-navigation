package dev.vector.geo.walk

import dev.vector.geo.Callouts
import dev.vector.geo.LngLat
import dev.vector.geo.RouteGeometry
import dev.vector.geo.signal.Approach
import dev.vector.geo.signal.MatchedSignal
import dev.vector.geo.signal.SignalMatcher
import dev.vector.geo.signal.SignalProfile
import dev.vector.geo.signal.SignalRef

/**
 * The surveyed signals standing on a walk's own crossings (V7 traffic lights).
 *
 * ## What this is
 *
 * The walking counterpart of `MainActivity.signalProfileFor`, and the one place
 * a walk's signal facts become placed map markers. It reads the 4B.4 contract's
 * cross maneuvers, takes the signals the BACKEND attached to each one, and
 * places them on the walk's own geometry.
 *
 * ## Why there is no matching here, and why that matters
 *
 * The car side has to match signals to a route: `/navigate` sends points and a
 * projection, and `SignalMatcher` resolves them. **A walk does not.** The
 * backend attaches a signal to a crossing only when the signal's own coordinate
 * is a vertex of the crossing way — graph node identity, zero metres wide — and
 * it sends the vertex `index` along with it. That index is in the same space as
 * `WalkCrossing.enterIndex`/`leaveIndex`, so the along-route position is
 * `RouteIndex.cum[index]`, read rather than computed.
 *
 * So this deliberately does NOT go near [SignalMatcher]'s projection or its
 * snap gate. There is nothing to snap: a signal that is not on the walk's
 * geometry is not a signal this walk crosses, and including one because it
 * happens to be 30 m from the route would be exactly the proximity claim the
 * association rule exists to refuse.
 *
 * [SignalMatcher.profile] is still used, for the one thing it does that is
 * needed here: validating a projected position against the route, ordering it
 * and merging duplicates. Its `Entry.alongM` half is the "already projected"
 * path, which is precisely what an identity-attached signal is.
 *
 * ## No state, ever
 *
 * The markers it produces carry [dev.vector.geo.signal.SignalText.LOCATION] and
 * nothing else, because [WalkSignal] has no field that could hold a phase. A
 * green marker cannot be drawn by this file even if someone later wanted one:
 * there is no data behind it and no type to carry it.
 */
object WalkSignals {

    /**
     * The signals on this walk, placed and ordered, or [SignalProfile.EMPTY].
     *
     * Empty on a real Qatar walk far more often than not — only 235 of the 430
     * foot-graph signals stand on a crossing at all, and most walks cross none
     * of them — and empty is the honest answer rather than a degraded one.
     */
    fun profile(route: WalkRoute): SignalProfile {
        val index = route.index ?: return SignalProfile.EMPTY
        val entries = ArrayList<SignalMatcher.Entry>()
        for (m in route.plan) {
            val crossing = m.crossing ?: continue
            for (s in crossing.signals) {
                val at = s.index ?: continue
                // Bounds-checked against the geometry. A backend index outside
                // it is a malformed payload, and a marker is not worth trusting
                // a coordinate we do not have.
                if (at < 0 || at >= index.coords.size) continue
                entries.add(
                    SignalMatcher.Entry(
                        ref = SignalRef(
                            id = s.id,
                            position = index.coords[at],
                            // The backend's own provenance, carried through
                            // unchanged: `osm:node:<id>`, what the survey says.
                            source = s.source,
                        ),
                        // The exact along-route position of the vertex the
                        // signal stands on. Not an estimate.
                        alongM = index.cum[at],
                        // Left null so the matcher derives the route's real
                        // bearing at that point from the geometry, rather than
                        // being handed a number this file invented.
                        approachBearingDeg = null,
                    )
                )
            }
        }
        if (entries.isEmpty()) return SignalProfile.EMPTY
        return SignalMatcher.profile(index, entries)
    }

    /**
     * The map callouts for this walk: one static pill per signal, in route order.
     *
     * Pure, so what appears on the map is testable without a renderer. The pills
     * are [Callouts.Kind.SIGNAL], which is the vocabulary the driving map
     * already uses for exactly this claim — a surveyed location — so the walking
     * map adds no second way of saying it.
     *
     * ## Why these are placed once and not windowed
     *
     * The driving callouts are windowed to a sliding span around the vehicle
     * because a long drive carries dozens of maneuvers and a symbol drawn at
     * constant screen size stacks them along the horizon. A walk carries none of
     * that pressure: measured on the real Qatar bake, a walk of a few hundred
     * metres has **0 or 1** signal-controlled crossings, and the whole route is
     * inside the walking frame. Rebuilding the source per fix would spend
     * uploads on a set that does not change, and the pill behind a walker who
     * has just crossed is still a true statement about the place.
     */
    fun callouts(route: WalkRoute, signals: SignalProfile = profile(route)): List<Callouts.Callout> {
        val index = route.index ?: return emptyList()
        if (signals.isEmpty) return emptyList()
        // `curvatureWarnings = false`: a walk gets the surveyed signals and
        // nothing else. See that parameter for why a driving bend warning is
        // not a walking fact.
        return Callouts.build(index, signals = signals.signals, curvatureWarnings = false)
    }

    /**
     * The surveyed position of one signal, for a marker or a test.
     *
     * Returns null when the walk has no geometry, when the maneuver carries no
     * signals, or when the index the backend sent is outside the geometry — the
     * three ways there is nothing to place.
     */
    fun positions(route: WalkRoute): List<LngLat> {
        val index = route.index ?: return emptyList()
        val out = ArrayList<LngLat>()
        for (m in route.plan) {
            for (s in m.crossing?.signals ?: emptyList()) {
                val at = s.index ?: continue
                if (at < 0 || at >= index.coords.size) continue
                out.add(index.coords[at])
            }
        }
        return out
    }
}
