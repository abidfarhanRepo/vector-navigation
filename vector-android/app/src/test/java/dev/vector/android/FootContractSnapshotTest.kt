package dev.vector.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The public `/foot` contract, as the real backend emits it (V7.4 4B.4).
 *
 * The snapshots are byte-for-byte responses captured from the real Qatar bake
 * on 2026-09-17 (`vector_routing.serve` with the 260912 foot graph, barriers
 * and crossings artifact):
 *
 *  - `vector-contract/foot-ordinary-4b4.json` — a 583 m walk with no
 *    crossings and no stairs (the plain everyday case);
 *  - `vector-contract/foot-crossing-4b4.json` — a 1,848 m walk with 208 m of
 *    crossing (the crossing-heavy case).
 *
 * Regenerate with the same curl commands that produced the 4B.4 evidence
 * (see `V7.4-EVIDENCE/pedestrian_contract_evidence.py`), and replace the
 * fixtures here. These tests pin TWO things that are easy to break quietly:
 *
 *  - the EXISTING client parser ([VectorApi.parseFootFeature]) still parses
 *    a real backend response — the 4B.4 additions are additive;
 *  - the contract fields the backend now promises (contract_version,
 *    walking_profile, cost with selection_factors, segment_cost_s aligned,
 *    deterministic maneuver_kinds) really are present in the actual JSON.
 *
 * No client behaviour is changed here: this is the audit + contract pin the
 * stage requires, not a rewrite of the walking UI (that is 4C).
 */
class FootContractSnapshotTest {

    private val api = VectorApi(base = "http://unused", token = "")

    private fun snapshot(name: String): JSONObject =
        JSONObject(javaClass.getResourceAsStream("/vector-contract/$name")
            ?.readBytes()?.decodeToString()
            ?: throw AssertionError("missing contract snapshot $name"))

    /** The single Feature inside the served FeatureCollection. */
    private fun feature(name: String): JSONObject =
        snapshot(name).getJSONArray("features").getJSONObject(0)

    private fun props(snapshot: JSONObject): JSONObject =
        snapshot.getJSONArray("features").getJSONObject(0).getJSONObject("properties")

    @Test
    fun `the existing parser reads a real ordinary walk`() {
        val leg = api.parseFootFeature(feature("foot-ordinary-4b4.json"))
        assertTrue(leg.segments.isNotEmpty())
        assertEquals(583.2, leg.distanceM, 0.15)
        assertEquals(432.0, leg.durationS, 0.15)
        assertEquals(0.0, leg.stepsM, 1e-9)
        // Segments carry the road classes the shade model needs.
        assertTrue(leg.segments.any { it.highway != null })
        // The reconstructed polyline matches the served geometry length.
        assertEquals(33, leg.geometry.size)
    }

    @Test
    fun `the existing parser reads a real crossing-heavy walk`() {
        val leg = api.parseFootFeature(feature("foot-crossing-4b4.json"))
        assertTrue(leg.segments.isNotEmpty())
        assertTrue(leg.distanceM >= 1800.0)
        assertTrue(leg.durationS >= 1300.0)
    }

    @Test
    fun `the contract markers are on the wire`() {
        val p = props(snapshot("foot-ordinary-4b4.json"))
        assertEquals("1", p.optString("contract_version"))
        assertEquals("general", p.optString("walking_profile"))
        // The MODE profile field keeps its pre-contract meaning.
        assertEquals("foot", p.optString("profile"))
        assertNotNull("cost decomposition must be present", p.optJSONObject("cost"))
    }

    @Test
    fun `the ETA field is the pure pace time, and the cost is separate`() {
        val p = props(snapshot("foot-ordinary-4b4.json"))
        val cost = p.getJSONObject("cost")
        // duration_s IS cost.pace_s (the contract's backward-compatible ETA
        // decision); the weighted cost and its penalties are separate fields.
        assertEquals(p.getDouble("duration_s"), cost.getDouble("pace_s"), 0.06)
        assertTrue(cost.getDouble("cost_s") >= cost.getDouble("pace_s"))
        // The requested profile's selection factors are explicit.
        assertEquals(
            listOf("stairs", "incline", "crossing_wait"),
            cost.getJSONArray("selection_factors").let { a ->
                (0 until a.length()).map { a.getString(it) }
            },
        )
    }

    @Test
    fun `a crossing-heavy walk reports crossing exposure, not inference`() {
        val p = props(snapshot("foot-crossing-4b4.json"))
        val cost = p.getJSONObject("cost")
        // crossing_m (4A.4) and the cost's crossing block agree.
        assertTrue(p.getDouble("crossing_m") > 200.0)
        assertEquals(22, cost.getJSONObject("crossing").getInt("edges"))
        assertTrue(cost.getJSONObject("crossing").getDouble("wait_s") > 100.0)
        // The crossing wait appears in the factor seconds — it AFFECTED choice.
        assertTrue(cost.getJSONObject("factor_s").has("crossing_wait"))
    }

    @Test
    fun `segment arrays stay aligned with the geometry`() {
        val p = props(snapshot("foot-crossing-4b4.json"))
        val n = p.getInt("nodes")
        for (key in listOf("classes", "footway", "crossing", "lit",
                           "enclosed", "area", "segment_cost_s")) {
            assertEquals("array $key must align", n - 1, p.getJSONArray(key).length())
        }
    }

    @Test
    fun `the maneuver plan uses only the documented vocabulary`() {
        val kinds = setOf("depart", "cross", "stairs", "turn_left", "turn_right",
                          "slight_left", "slight_right", "uturn", "continue", "arrive")
        val p = props(snapshot("foot-crossing-4b4.json"))
        val plan = p.getJSONArray("maneuver_plan")
        assertTrue(plan.length() >= 5)
        for (i in 0 until plan.length()) {
            val kind = plan.getJSONObject(i).getString("kind")
            assertTrue("unexpected maneuver kind $kind", kinds.contains(kind))
        }
        // depart first, arrive last; positions are non-decreasing.
        assertEquals("depart", plan.getJSONObject(0).getString("kind"))
        assertEquals("arrive", plan.getJSONObject(plan.length() - 1).getString("kind"))
        var lastIndex = -1
        for (i in 0 until plan.length()) {
            val idx = plan.getJSONObject(i).getInt("index")
            assertTrue(idx >= lastIndex)
            lastIndex = idx
        }
    }

    @Test
    fun `absent OSM attributes stay absent on the crossing-heavy walk`() {
        val p = props(snapshot("foot-crossing-4b4.json"))
        val cost = p.getJSONObject("cost")
        // This walk has no stairs: the exposure block is present with honest
        // zeros, and the maneuver plan has no stairs maneuver with invented
        // step facts.
        assertEquals(0, cost.getJSONObject("stairs").getInt("edges"))
        val plan = p.getJSONArray("maneuver_plan")
        for (i in 0 until plan.length()) {
            assertFalse("no invented stairs",
                plan.getJSONObject(i).optString("kind") == "stairs")
        }
    }
}