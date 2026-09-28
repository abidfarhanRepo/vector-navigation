package dev.vector.android

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Journeys the driver has actually driven.
 *
 * ## What was missing
 *
 * [Journey] persists the drive **in progress**, so a process death mid-route is
 * survivable. Nothing persisted a drive once it was over. `arrive()` built an
 * [ArrivalSummary] — destination, distance, planned time, actual time — put it
 * on screen, and dropped every number on the floor the moment the driver
 * dismissed the card. So Vector could tell you, once, how the drive you had
 * just finished compared with its prediction, and then had no memory that the
 * drive had ever happened.
 *
 * V6 §13.D asks for the opposite property: *"The user should be able to reopen
 * Vector later and still see their previous drives."*
 *
 * ## Why JSON here and delimited strings in [Recents]
 *
 * [Recents] and [Places] store three fields and use one delimited string,
 * which is the right call for three fields — and both needed a test proving a
 * name containing the delimiter cannot corrupt the list, because with a
 * positional format one bad field shifts every field after it.
 *
 * A drive has ten, four of which are optional. Positional encoding of that is
 * a format nobody can extend without a migration, and a single malformed
 * record would take the whole history with it. So: one JSON array, parsed
 * **per entry**, and an entry that will not parse is skipped while its
 * neighbours survive. That is the §14 requirement — a persistence failure must
 * degrade, not cascade — expressed in the storage format rather than bolted on
 * afterwards.
 *
 * ## Unavailable is a value
 *
 * §13.D: *"If certain information was unavailable during a drive, represent it
 * as unavailable rather than inventing it."* Three fields are genuinely
 * unknowable on some drives and are nullable for that reason:
 *
 * * [Drive.startLng] / [Drive.startLat] — null when navigation began before
 *   the first fix landed. Rare, and not the same as (0, 0).
 * * [Drive.plannedS] — null when the router gave no duration, so
 *   [Drive.verdict] withholds a comparison rather than comparing against zero
 *   and reporting the whole drive as an overrun.
 *
 * This never leaves the device, and it is the same consent [Recents] already
 * operates under: a destination is persisted there, and this holds a list of
 * them with times attached.
 */
object Drives {

    /**
     * How many drives are kept.
     *
     * Twenty. §13.C's warning against "an uncontrolled database dump" applies
     * here more than it does to recents: a drive record is ten fields, a
     * navigator is used twice a day, and nobody scrolls a journey log. Twenty
     * is about a fortnight of commuting — enough that the list answers "when
     * did I last go there", short enough that it stays one screen and one
     * bounded string in a preferences file.
     *
     * Bounded by COUNT rather than by age, deliberately. An age cut-off would
     * quietly empty the list for somebody who did not drive for a month, which
     * is the moment the history is most interesting.
     */
    const val LIMIT = 20

    private const val KEY = "drives"

    /**
     * One completed or abandoned journey.
     *
     * @property completed true when the drive ended by arriving. False when the
     *   driver tapped out of it. Both are recorded: "I set off for the airport
     *   and gave up" is a real journey and hiding it would make the log a
     *   record of successes rather than of drives.
     * @property distanceM how far along the route the drive actually got. On an
     *   arrival that is the route's own distance; on an abandoned drive it is
     *   the distance covered, not the distance planned.
     * @property durationS measured from the moment Start was tapped, by the
     *   wall clock. Includes time at red lights and time parked mid-journey,
     *   because that is what the driver's own watch would have said.
     * @property plannedS what the router predicted for the route being driven
     *   when the drive ended, or null when it never gave a figure.
     */
    data class Drive(
        val destination: String,
        val destLng: Double,
        val destLat: Double,
        val startLng: Double?,
        val startLat: Double?,
        val startedAtMs: Long,
        val endedAtMs: Long,
        val distanceM: Double,
        val durationS: Double,
        val plannedS: Double?,
        val completed: Boolean,
        /**
         * The road actually driven, as the positions the receiver reported.
         *
         * ## Why the history stores a shape at all
         *
         * The log answered "where did I go and what did it cost" and could not
         * answer "which way did I go" — which is the question a driver asks
         * about a journey they remember being slow, or one they want to repeat
         * exactly. Requested directly: *"it would be nice if the past drives
         * shows the exact routes i took to reach my destination."*
         *
         * It is the TRACK, not the plan. The planned route is what Vector
         * suggested; this is where the car was, so a detour, a missed turn and
         * a reroute are all in it — which is the entire difference between a
         * history and a repeat of the recommendation. A drive that went a way
         * the router never proposed draws that way.
         *
         * ## Why it is [LngLat] pairs and not an encoded polyline
         *
         * Encoded polyline would be ~40 % smaller and needs a codec on both
         * sides; the cap below makes the saving irrelevant. Twenty drives at
         * [TRACK_MAX_POINTS] is about 55 kB of JSON in a preferences file whose
         * other contents are already larger than that, and a plain array is
         * readable in a bug report.
         *
         * Empty for every drive recorded before this shipped, and for a drive
         * that never got a fix. Both are normal and the UI draws nothing.
         */
        val track: List<dev.vector.geo.LngLat> = emptyList(),
    ) {
        /**
         * How this drive compared with its prediction, or null.
         *
         * Same rule and the same margin as [ArrivalSummary.etaVerdict], and for
         * the same reason: inside 90 seconds the difference is one traffic
         * light and the moment the driver happened to tap Start, not a
         * statement about the routing engine. Null when there was no prediction
         * to compare against, or when the drive was abandoned — an abandoned
         * drive took less time than predicted by definition, and reporting that
         * as the router being pessimistic would be a fabrication.
         */
        val verdict: String?
            get() {
                if (!completed) return null
                val planned = plannedS ?: return null
                if (planned <= 0.0) return null
                val d = durationS - planned
                if (kotlin.math.abs(d) < ETA_HONEST_MARGIN_S) return "as predicted"
                val mins = Math.round(kotlin.math.abs(d) / 60.0).toInt().coerceAtLeast(1)
                return if (d > 0) "$mins min over" else "$mins min under"
            }
    }

    /**
     * The history, newest first.
     *
     * Never throws. A preferences value that is not an array, or is not JSON at
     * all, reads as an empty history — because the alternative is an app that
     * will not launch, and §14 rules that out explicitly.
     */
    fun load(p: SharedPreferences): List<Drive> {
        val raw = p.getString(KEY, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Drive>(arr.length())
        for (i in 0 until arr.length()) {
            // Per ENTRY. One record written by a build with a different shape,
            // or truncated by a storage failure, must cost that record and not
            // the list.
            val o = arr.optJSONObject(i) ?: continue
            val d = runCatching { parse(o) }.getOrNull() ?: continue
            out.add(d)
        }
        // Sorted rather than trusted. The write path prepends, so the stored
        // order is already newest-first — but a file merged across an upgrade,
        // or written by a build whose clock had moved, would otherwise present
        // a history in an order the driver cannot explain.
        out.sortByDescending { it.endedAtMs }
        return if (out.size > LIMIT) out.subList(0, LIMIT).toList() else out
    }

    /**
     * Record a drive, newest first, and return the new history.
     *
     * No de-duplication, unlike [Recents]. Driving to the same place twice is
     * two journeys and the second one does not replace the first — that is the
     * whole difference between a history and a destination list.
     */
    fun add(p: SharedPreferences, drive: Drive): List<Drive> {
        val next = (listOf(drive) + load(p)).take(LIMIT)
        write(p, next)
        return next
    }

    /**
     * Forget one drive.
     *
     * Identified by when it ended, which is unique to the millisecond for any
     * history a person can produce, rather than by an index into a list the
     * caller may have re-read since.
     */
    fun remove(p: SharedPreferences, endedAtMs: Long): List<Drive> {
        val next = load(p).filterNot { it.endedAtMs == endedAtMs }
        write(p, next)
        return next
    }

    fun clear(p: SharedPreferences) {
        // commit(), not apply(): see Settings.save. A driver who clears their
        // journey history and force-stops the app has asked for it to be gone,
        // and an asynchronous write that had not landed yet would bring it
        // back on the next launch.
        p.edit().remove(KEY).commit()
    }

    private fun write(p: SharedPreferences, drives: List<Drive>) {
        val arr = JSONArray()
        for (d in drives) arr.put(encode(d))
        p.edit().putString(KEY, arr.toString()).commit()
    }

    private fun encode(d: Drive): JSONObject = JSONObject().apply {
        put("name", d.destination)
        put("dlng", d.destLng)
        put("dlat", d.destLat)
        // Omitted rather than written as null: `optDouble` on an absent key and
        // on a JSON null both give NaN, so the two are equivalent on read, and
        // leaving them out keeps the stored string smaller.
        d.startLng?.let { put("slng", it) }
        d.startLat?.let { put("slat", it) }
        put("t0", d.startedAtMs)
        put("t1", d.endedAtMs)
        put("dist", d.distanceM)
        put("dur", d.durationS)
        d.plannedS?.let { put("plan", it) }
        put("done", d.completed)
        // Flat [lng, lat, lng, lat, …] rather than an array of objects or of
        // pairs: a thousand two-element arrays is a thousand allocations on
        // read for a shape that is only ever consumed as a sequence.
        if (d.track.isNotEmpty()) {
            val t = JSONArray()
            for (pt in d.track) { t.put(pt.lng); t.put(pt.lat) }
            put("trk", t)
        }
    }

    private fun parse(o: JSONObject): Drive? {
        val endedAt = o.optLong("t1", 0L)
        // A drive with no end time cannot be ordered, and ordering is the one
        // property the list has to get right. Treat it as unreadable.
        if (endedAt <= 0L) return null
        val dlng = o.optDouble("dlng", Double.NaN)
        val dlat = o.optDouble("dlat", Double.NaN)
        if (dlng.isNaN() || dlat.isNaN()) return null
        return Drive(
            destination = o.optString("name", "").ifBlank { "Unnamed destination" },
            destLng = dlng,
            destLat = dlat,
            startLng = o.optDouble("slng", Double.NaN).takeIf { !it.isNaN() },
            startLat = o.optDouble("slat", Double.NaN).takeIf { !it.isNaN() },
            startedAtMs = o.optLong("t0", 0L),
            endedAtMs = endedAt,
            distanceM = o.optDouble("dist", 0.0).takeIf { !it.isNaN() } ?: 0.0,
            durationS = o.optDouble("dur", 0.0).takeIf { !it.isNaN() } ?: 0.0,
            plannedS = o.optDouble("plan", Double.NaN).takeIf { !it.isNaN() && it > 0.0 },
            completed = o.optBoolean("done", false),
            track = parseTrack(o.optJSONArray("trk")),
        )
    }

    /**
     * The stored shape, or nothing.
     *
     * Tolerant on purpose, like everything else on this read path: an odd
     * length (a write truncated mid-pair), a non-numeric entry or a NaN costs
     * that POINT and not the drive. A journey whose shape is half-readable is
     * still a journey the driver wants in the list, and the row degrades to
     * "no map" rather than disappearing.
     */
    private fun parseTrack(arr: JSONArray?): List<dev.vector.geo.LngLat> {
        if (arr == null || arr.length() < 4) return emptyList()
        val out = ArrayList<dev.vector.geo.LngLat>(arr.length() / 2)
        var i = 0
        while (i + 1 < arr.length()) {
            val lng = arr.optDouble(i, Double.NaN)
            val lat = arr.optDouble(i + 1, Double.NaN)
            if (!lng.isNaN() && !lat.isNaN()) out.add(dev.vector.geo.LngLat(lng, lat))
            i += 2
        }
        return out
    }

    /**
     * How many points a recorded track keeps.
     *
     * 400. At the sampling distance below that is roughly 10 km of city driving
     * at full resolution, and a longer journey is decimated rather than
     * truncated (see `MainActivity.appendTrackPoint`) so the shape of the whole
     * drive survives instead of the first ten kilometres of it.
     */
    const val TRACK_MAX_POINTS = 400

    /**
     * How far the car moves before another point is kept.
     *
     * 25 m. The receiver reports about once a second, which at 100 km/h is
     * every 28 m and in stationary traffic is every 0 m — so sampling by TIME
     * stores three hundred identical points for a red light and misses the
     * shape of a fast sweeping curve. Sampling by DISTANCE spends the budget
     * where the road actually changes direction.
     */
    const val TRACK_MIN_SPACING_M = 25.0
}
