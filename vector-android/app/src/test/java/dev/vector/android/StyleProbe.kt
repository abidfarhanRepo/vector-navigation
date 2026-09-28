package dev.vector.android

import org.json.JSONArray
import org.json.JSONObject

/**
 * Asks the real style document the questions MapLibre would ask it.
 *
 * ## Why this exists rather than a `contains("lanes")`
 *
 * A `symbol-sort-key` that mentions "gas_station" is not the same claim as a
 * gas station outranking a laundry, and a `line-width` that mentions `lanes` is
 * not the same claim as a three-lane road being drawn three lanes wide.
 * `VectorStyleDensityTest` established the pattern — evaluate the compiled
 * expression against a real feature's properties at a stated zoom, which is the
 * same three inputs the renderer has — and this is that interpreter, moved
 * somewhere more than one test file can reach it.
 *
 * It is deliberately a small subset: it knows the operators this style actually
 * uses and throws on anything else, so a new operator arrives as a failing test
 * rather than as a silently wrong answer.
 */
class StyleProbe(
    private val theme: VectorStyle.MapTheme = VectorStyle.MapTheme.DARK,
) {
    val doc: JSONObject =
        JSONObject(VectorStyle.json("http://host:9003", 42L, 6, 15, theme))

    val layers: List<JSONObject> = doc.getJSONArray("layers").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }
    }

    val ids: List<String> = layers.map { it.getString("id") }

    fun layer(id: String): JSONObject =
        layers.firstOrNull { it.getString("id") == id }
            ?: throw AssertionError("no layer `$id` in the $theme style: $ids")

    fun paint(id: String, key: String): Any? = layer(id).optJSONObject("paint")?.opt(key)

    fun layout(id: String, key: String): Any? = layer(id).optJSONObject("layout")?.opt(key)

    /** A paint number, evaluated for [f] at [zoom]. */
    fun num(id: String, key: String, f: Map<String, Any?> = emptyMap(), zoom: Double): Double =
        (eval(paint(id, key) ?: throw AssertionError("$id has no $key"), f, zoom) as Number)
            .toDouble()

    fun width(id: String, f: Map<String, Any?> = emptyMap(), zoom: Double): Double =
        num(id, "line-width", f, zoom)

    fun offset(id: String, f: Map<String, Any?> = emptyMap(), zoom: Double): Double =
        num(id, "line-offset", f, zoom)

    /** Does [id] draw [f] at [zoom]? Filter and minzoom, which is what MapLibre asks. */
    fun drawn(id: String, f: Map<String, Any?> = emptyMap(), zoom: Double): Boolean {
        val l = layer(id)
        if (zoom < l.optInt("minzoom", 0)) return false
        if (zoom > l.optInt("maxzoom", 25)) return false
        val filter = l.opt("filter") ?: return true
        return eval(filter, f, zoom) == true
    }

    fun eval(e: Any?, f: Map<String, Any?>, zoom: Double): Any? {
        if (e !is JSONArray) return if (e == JSONObject.NULL) null else e
        val args = (1 until e.length()).map { e.get(it) }
        fun ev(x: Any?) = eval(x, f, zoom)
        // MapLibre's `to-number` converts a numeric STRING, which matters here
        // more than anywhere else in the style: `lanes` arrives from the MVT as
        // the string OSM wrote it, never as an integer.
        fun n(x: Any?): Double = when (val v = ev(x)) {
            is Number -> v.toDouble()
            is String -> v.toDouble()
            is Boolean -> if (v) 1.0 else 0.0
            else -> throw IllegalArgumentException("not a number: $v")
        }
        return when (val op = e.getString(0)) {
            "zoom" -> zoom
            "get" -> f[ev(args[0]) as String]
            "has" -> f.containsKey(ev(args[0]) as String)
            "literal" -> args[0]
            "all" -> args.all { ev(it) == true }
            "any" -> args.any { ev(it) == true }
            "!" -> ev(args[0]) != true
            // Numerically when both sides are numbers. MapLibre holds every
            // number as a double, so `["to-number", ...]` yielding 2.0 equals
            // the literal 2 there; in Kotlin an `Any?` comparison of Double 2.0
            // to Integer 2 is false, and taking that at face value would report
            // a lane divider missing that the renderer draws.
            "==" -> {
                val a = ev(args[0]); val b = ev(args[1])
                if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b
            }
            "!=" -> {
                val a = ev(args[0]); val b = ev(args[1])
                if (a is Number && b is Number) a.toDouble() != b.toDouble() else a != b
            }
            "<" -> n(args[0]) < n(args[1])
            ">" -> n(args[0]) > n(args[1])
            "<=" -> n(args[0]) <= n(args[1])
            ">=" -> n(args[0]) >= n(args[1])
            "+" -> args.sumOf { n(it) }
            "-" -> n(args[0]) - n(args[1])
            "*" -> args.fold(1.0) { acc, x -> acc * n(x) }
            "to-number" -> n(args[0])
            "downcase" -> (ev(args[0]) as String).lowercase()
            "concat" -> args.joinToString("") { ev(it)?.toString() ?: "" }
            "index-of" -> (ev(args[1]) as String).indexOf(ev(args[0]) as String)
            "coalesce" -> args.firstNotNullOfOrNull { ev(it) }
            "case" -> {
                var i = 0
                var out: Any? = null
                while (i + 1 < args.size) {
                    if (ev(args[i]) == true) { out = ev(args[i + 1]); break }
                    i += 2
                }
                out ?: ev(args.last())
            }
            "step" -> {
                val input = n(args[0])
                var out = ev(args[1])
                var i = 2
                while (i + 1 < args.size) {
                    if (input >= n(args[i])) out = ev(args[i + 1])
                    i += 2
                }
                out
            }
            // Base 2 is the one that matters: Web Mercator's scale doubles per
            // zoom step, so two correct stops under base 2 are correct at every
            // zoom between and beyond them.
            "interpolate" -> {
                val base = (args[0] as JSONArray).let {
                    if (it.getString(0) == "exponential") it.getDouble(1) else 1.0
                }
                val input = n(args[1])
                val stops = (2 until args.size).step(2).map { n(args[it]) to n(args[it + 1]) }
                when {
                    input <= stops.first().first -> stops.first().second
                    input >= stops.last().first -> stops.last().second
                    else -> {
                        val hi = stops.indexOfFirst { it.first >= input }
                        val (z0, v0) = stops[hi - 1]
                        val (z1, v1) = stops[hi]
                        val t = if (base == 1.0) (input - z0) / (z1 - z0)
                        else (Math.pow(base, input - z0) - 1) / (Math.pow(base, z1 - z0) - 1)
                        v0 + t * (v1 - v0)
                    }
                }
            }
            "match" -> {
                val input = ev(args[0])
                var i = 1
                var out: Any? = null
                while (i + 1 < args.size) {
                    val labels = args[i]
                    val hit = if (labels is JSONArray)
                        (0 until labels.length()).any { labels.get(it) == input }
                    else labels == input
                    if (hit) { out = ev(args[i + 1]); break }
                    i += 2
                }
                out ?: ev(args.last())
            }
            else -> throw IllegalArgumentException("StyleProbe does not know `$op`")
        }
    }

    companion object {
        /** A road as the tile carries it. `lanes` is a STRING in OSM, as in MVT. */
        fun road(
            lanes: Int? = null,
            highway: String = "primary",
            bridge: String? = null,
            tunnel: String? = null,
            car: Boolean = true,
        ): Map<String, Any?> = buildMap {
            put("kind", "road")
            put("highway", highway)
            put("car", car)
            if (lanes != null) put("lanes", lanes.toString())
            if (bridge != null) put("bridge", bridge)
            if (tunnel != null) put("tunnel", tunnel)
        }

        /** CIE L\*, the perceptual lightness the palette assertions are made in. */
        fun lstar(hex: String): Double {
            fun lin(c: Int): Double {
                val s = c / 255.0
                return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            val v = hex.removePrefix("#")
            val r = lin(v.substring(0, 2).toInt(16))
            val g = lin(v.substring(2, 4).toInt(16))
            val b = lin(v.substring(4, 6).toInt(16))
            val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
            return if (y <= 0.008856) 903.3 * y else 116 * Math.cbrt(y) - 16
        }
    }
}
