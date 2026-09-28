package dev.vector.android

/**
 * What a dropped pin is called.
 *
 * ## What this exists to stop
 *
 * `/reverse` answers "nearest named features to a point" — every named OSM way
 * and every Overture place, ranked by distance alone, with no radius. Handing
 * its first row straight to the destination chip made the server's ranking the
 * product decision, and the ranking has no opinion about what the answer is
 * FOR. Measured against production on 2026-09-21:
 *
 * | pin dropped at | nearest feature | distance | what the chip said |
 * |---|---|---|---|
 * | 25.2720, 51.5330 (Mansoura street) | `Princess Ladies Saloon` | 13.5 m | a beauty salon, not the street |
 * | 24.9000, 51.1000 (open desert) | `Umm Hotta` | **3,528 m** | a village 3.5 km away |
 * | 25.2560, 51.4460 (Al Waab) | `mohamed abdullah` | 17.3 m | an OSM node name |
 *
 * The last one is worse than the first two because it is not far away, it is
 * simply not a place: `fetch_qatar_pbf.py` promotes every named `building=*`
 * way to a POI, so a resident's name tag becomes a destination.
 *
 * ## The rule
 *
 * A dropped pin is for somewhere with no name — that is the whole reason the
 * gesture exists, and the reference products agree: both Google and Waze answer
 * a long-press with a *street*, not with the shop next door. So:
 *
 *  1. the nearest **road** within [ROAD_MAX_M] names the pin;
 *  2. otherwise the nearest named feature of any kind within [NEAR_MAX_M];
 *  3. otherwise the caller's fallback — "Dropped pin" — is the honest answer,
 *     and nothing is invented.
 *
 * [ROAD_MAX_M] is generous relative to [NEAR_MAX_M] because a road feature is
 * indexed at **one vertex of one way** (`index.py`: `lon, lat = ring[0]`), not
 * at its nearest point: a pin on the middle of a 2 km stretch of Al Corniche
 * can be hundreds of metres from every segment's first node, and the way whose
 * vertex happens to be closest is still the road the driver is on. A POI has
 * exact point geometry, so its bound can be tight.
 *
 * Pure and Android-free on purpose, so the boundary cases are pinned by
 * `PinNameTest` on the JVM rather than argued about from a screenshot.
 */
object PinName {

    /** A road this close names the pin. See the class doc for why it is loose. */
    const val ROAD_MAX_M = 150.0

    /**
     * A non-road feature this close is AT the pin rather than near it.
     *
     * Tighter than [ROAD_MAX_M] by an order of magnitude, and deliberately: a
     * shop 60 m away is a guess about which frontage the driver meant, and the
     * defect this file exists to fix was a shop 13.5 m away being treated as
     * the address.
     */
    const val NEAR_MAX_M = 60.0

    private const val KIND_ROAD = "road"

    /**
     * The name for a pin at the coordinate [hits] were resolved from.
     *
     * [hits] need not be sorted — the nearest of each kind is chosen explicitly,
     * so a server that changes its ordering cannot change the label.
     */
    fun choose(hits: List<VectorApi.ReverseHit>, fallback: String): String {
        val named = hits.filter { it.name.isNotBlank() && it.distanceM.isFinite() }
        val road = named
            .filter { it.kind == KIND_ROAD && it.distanceM <= ROAD_MAX_M }
            .minByOrNull { it.distanceM }
        if (road != null) return road.name
        val near = named
            .filter { it.distanceM <= NEAR_MAX_M }
            .minByOrNull { it.distanceM }
        return near?.name ?: fallback
    }
}
