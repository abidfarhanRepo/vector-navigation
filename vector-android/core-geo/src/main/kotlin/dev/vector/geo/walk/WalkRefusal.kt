package dev.vector.geo.walk

/**
 * Why there is no walking route, as three genuinely different facts
 * (V7.4 4C final).
 *
 * ## Why three and not one message
 *
 * They call for three different responses, and collapsing them would throw
 * away the one distinction the backend went to trouble to preserve. 4A.1 made
 * a disconnected pedestrian pair answer **HTTP 404 with
 * `reason: pedestrian_network_split`** rather than the generic refusal,
 * precisely so a client could tell them apart — and until this stage nothing
 * ever read it.
 *
 * On Qatar's graph the distinction is not academic. The pedestrian network has
 * **2,811 connected components and the largest holds 32% of nodes**, so a
 * split is the COMMON refusal rather than an exotic one, and it is the one
 * case where the honest answer is about the MAP rather than about the request.
 *
 * ## What none of them may do
 *
 * None of these states may draw a line. The brief's rule — "do not draw a
 * misleading straight line as a walking route" — is what a refusal is FOR: a
 * straight line between two points on separate pedestrian networks is a claim
 * that a person can walk between them, which is the exact thing the backend
 * refused to say. There is no geometry anywhere in this file.
 *
 * Nor may any of them silently fall back to car routing. A driving route
 * between two points says nothing about whether a person can walk it, and
 * presenting one in answer to a walking request would be answering a question
 * nobody asked.
 */
enum class WalkRefusalKind {
    /**
     * The two points are on separate pedestrian networks.
     *
     * The backend's `pedestrian_network_split`. A statement about the map: no
     * continuous walkable path exists between these points in the data Vector
     * has, usually because the arterials that would connect them are roads a
     * person may not walk along and OSM has no crossing mapped.
     */
    NETWORK_SPLIT,

    /** The generic refusal: the router could not produce a walk. */
    NO_ROUTE,

    /**
     * The request never got an answer.
     *
     * Distinct from both above because it says nothing at all about whether a
     * walk exists — the question was not reached. Retrying is sensible here
     * and pointless for the other two, which is the whole reason it is a
     * separate state.
     */
    BACKEND_FAILURE,
}

/**
 * What a person is told when there is no walking route (V7.4 4C final).
 *
 * Pure text, in core-geo, so the wording is unit-testable and so there is one
 * copy of it. Each line says what happened and what it means, and none of them
 * claims a connection Vector cannot establish.
 */
object WalkRefusalText {

    /** The headline. Short enough for a banner. */
    fun title(kind: WalkRefusalKind): String = when (kind) {
        WalkRefusalKind.NETWORK_SPLIT -> "No continuous walking route"
        WalkRefusalKind.NO_ROUTE -> "No walking route found"
        WalkRefusalKind.BACKEND_FAILURE -> "Could not plan the walk"
    }

    /**
     * The explanation.
     *
     * The split case says what is actually true — the paths Vector knows about
     * do not connect — and explicitly does **not** say "you cannot walk there".
     * A person may well be able to; the map cannot show a route, and those are
     * different statements. Saying the second would be inventing knowledge
     * about the world from the absence of data.
     */
    fun detail(kind: WalkRefusalKind): String = when (kind) {
        WalkRefusalKind.NETWORK_SPLIT ->
            "The footpaths Vector knows about don't connect these two points"
        WalkRefusalKind.NO_ROUTE ->
            "Vector could not find a walking route between these points"
        WalkRefusalKind.BACKEND_FAILURE ->
            "Vector could not be reached for a walking route"
    }

    /** True when trying again could plausibly give a different answer. */
    fun retryable(kind: WalkRefusalKind): Boolean =
        kind == WalkRefusalKind.BACKEND_FAILURE

    /**
     * The spoken form, or null when nothing should be said.
     *
     * One short sentence, and only for a refusal the walker is waiting on. It
     * never speculates about a way round and never suggests driving instead:
     * whether a car route exists is a different question with a different
     * answer, and offering one unasked is the silent fallback the brief rules
     * out.
     */
    fun spoken(kind: WalkRefusalKind): String = when (kind) {
        WalkRefusalKind.NETWORK_SPLIT ->
            "No continuous walking route was found between these points"
        WalkRefusalKind.NO_ROUTE -> "No walking route was found"
        WalkRefusalKind.BACKEND_FAILURE -> "Vector could not plan the walk"
    }
}
