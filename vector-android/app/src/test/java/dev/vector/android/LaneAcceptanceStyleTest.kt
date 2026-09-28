package dev.vector.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The V8 lane-marking acceptance path: OFF by default, correct when ON, and
 * invisible to every existing client.
 *
 * These pin the four things the acceptance review requires:
 *
 *  1. the default style is byte-identical to the explicit `lanes = false`
 *     style, so an ordinary build (and MainActivity) is unchanged;
 *  2. `lanes = true` ADDS the `lanes` source-layer layers and changes nothing
 *     else — the default style's layers are a subset, in order;
 *  3. the markings carry no `line-offset` (the offset is in the geometry), read
 *     `source-layer: "lanes"`, and start at z15; and
 *  4. the result is still a single valid style document with unique ids.
 */
class LaneAcceptanceStyleTest {

    private fun style(lanes: Boolean): JSONObject =
        JSONObject(VectorStyle.json("http://host:9003", 42L, 6, 15,
            VectorStyle.MapTheme.DARK, lanes = lanes))

    private fun layers(s: JSONObject): List<JSONObject> {
        val a: JSONArray = s.getJSONArray("layers")
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    private fun ids(s: JSONObject): List<String> = layers(s).map { it.getString("id") }

    private fun laneLayers(s: JSONObject): List<JSONObject> =
        layers(s).filter { it.getString("id").startsWith("lanes-") }

    @Test
    fun `the default style has no lane layers`() {
        // The parameter defaults to BuildConfig.LANES, which is false in every
        // build but an acceptance one, so the default document draws no lanes.
        val defaultIds = ids(JSONObject(VectorStyle.json("http://host:9003", 42L, 6, 15)))
        assertTrue("the default build must not emit a `lanes` layer: $defaultIds",
            defaultIds.none { it.startsWith("lanes-") })
    }

    @Test
    fun `the default style equals the explicit lanes-off style byte for byte`() {
        val default = VectorStyle.json("http://host:9003", 42L, 6, 15)
        val off = VectorStyle.json("http://host:9003", 42L, 6, 15,
            VectorStyle.MapTheme.DARK, lanes = false)
        assertEquals(off, default)
    }

    @Test
    fun `turning lanes on adds only lane layers and keeps the rest in order`() {
        val offIds = ids(style(lanes = false))
        val onIds = ids(style(lanes = true))
        // Every non-lane layer that is on when lanes are off is still there, in
        // the same relative order: lanes-on is a pure superset.
        assertEquals(offIds, onIds.filterNot { it.startsWith("lanes-") })
        // And it added exactly the six lane layers.
        assertEquals(
            listOf("lanes-ground-lane", "lanes-ground-centre", "lanes-ground-solid",
                   "lanes-bridge-lane", "lanes-bridge-centre", "lanes-bridge-solid"),
            onIds.filter { it.startsWith("lanes-") })
    }

    @Test
    fun `every lane layer reads the lanes source-layer at z15 with no line-offset`() {
        for (l in laneLayers(style(lanes = true))) {
            val id = l.getString("id")
            assertEquals("$id source", "vector", l.getString("source"))
            assertEquals("$id source-layer", "lanes", l.getString("source-layer"))
            assertEquals("$id type", "line", l.getString("type"))
            assertEquals("$id minzoom", 15, l.getInt("minzoom"))
            val paint = l.getJSONObject("paint")
            assertFalse("$id must not use line-offset — the offset is in the geometry",
                paint.has("line-offset"))
        }
    }

    @Test
    fun `the dashed classes dash and the solid class does not`() {
        val byId = laneLayers(style(lanes = true)).associateBy { it.getString("id") }
        for (dashed in listOf("lanes-ground-lane", "lanes-ground-centre",
                              "lanes-bridge-lane", "lanes-bridge-centre")) {
            assertTrue("$dashed should be dashed",
                byId.getValue(dashed).getJSONObject("paint").has("line-dasharray"))
        }
        for (solid in listOf("lanes-ground-solid", "lanes-bridge-solid")) {
            assertFalse("$solid should be solid",
                byId.getValue(solid).getJSONObject("paint").has("line-dasharray"))
        }
    }

    @Test
    fun `ground markings sit above the road surface and below the bridge group`() {
        val order = ids(style(lanes = true))
        val groundLane = order.indexOf("lanes-ground-lane")
        val carriagewayDeck = order.indexOf("carriageway")
        val bridgeDeck = order.indexOf("carriageway-bridge")
        val bridgeLane = order.indexOf("lanes-bridge-lane")
        assertTrue("ground markings must be above their own deck",
            groundLane > carriagewayDeck)
        assertTrue("ground markings must be below the bridge deck",
            groundLane < bridgeDeck)
        assertTrue("bridge markings must be above the bridge deck",
            bridgeLane > bridgeDeck)
    }

    @Test
    fun `the lanes-on style is valid with unique ids and stays below the labels`() {
        val s = style(lanes = true)
        assertEquals(8, s.getInt("version"))
        val order = ids(s)
        assertEquals("duplicate layer ids with lanes on", order.size, order.toSet().size)
        // Labels are drawn on top of the markings, as they are on top of roads.
        val lastLane = order.indexOfLast { it.startsWith("lanes-") }
        val firstLabel = order.indexOfFirst { it.contains("label", ignoreCase = true) }
        assertTrue("a label layer should exist", firstLabel >= 0)
        assertTrue("lane markings must be below the labels", lastLane < firstLabel)
    }

    @Test
    fun `both themes emit the lane layers`() {
        for (theme in listOf(VectorStyle.MapTheme.DARK, VectorStyle.MapTheme.LIGHT)) {
            val s = JSONObject(VectorStyle.json("http://host:9003", 42L, 6, 15, theme,
                lanes = true))
            assertEquals(6, layers(s).count { it.getString("id").startsWith("lanes-") })
        }
    }
}
