package dev.vector.android

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The jagged-edge fix, as the style sees it (V8 native acceptance §9.4).
 *
 * Two halves. The bake's `--taper` hands the last stretch of a wider way over
 * as pieces carrying `lw`, a fractional lane count, and every carriageway
 * width must follow it so surface, kerb and skirt narrow together. And the
 * ground and ring groups end in ROUND caps, which close the wedge an angled
 * merge or diverge left between two square ends, while the bridge group keeps
 * BUTT caps so a flyover's kerb is not ruled across its approach.
 */
class CarriagewayTaperTest {

    private val dark = StyleProbe(VectorStyle.MapTheme.DARK)
    private val z = 17.5

    private fun piece(lanes: Int?, lw: Double, bridge: String? = null) =
        StyleProbe.road(lanes, "primary", bridge, null, true) + ("lw" to lw)

    private val groundWidths = listOf("carriageway", "carriageway-casing", "carriageway-skirt")
    private val bridgeWidths = listOf("carriageway-bridge", "carriageway-bridge-casing", "carriageway-bridge-skirt")

    @Test
    fun `a taper piece is drawn at its lw width on the deck, the kerb and the skirt`() {
        for (id in groundWidths) {
            val two = dark.width(id, StyleProbe.road(2), z)
            val three = dark.width(id, StyleProbe.road(3), z)
            val mid = dark.width(id, piece(3, 2.5), z)
            assertEquals("$id at lw 2.5", (two + three) / 2.0, mid, 1e-6)
        }
    }

    @Test
    fun `lw is read on bridges too, so a flyover that narrows tapers as well`() {
        for (id in bridgeWidths) {
            val two = dark.width(id, StyleProbe.road(2, bridge = "yes"), z)
            val four = dark.width(id, StyleProbe.road(4, bridge = "yes"), z)
            assertEquals(id, (two + four) / 2.0, dark.width(id, piece(4, 3.0, bridge = "yes"), z), 1e-6)
        }
    }

    @Test
    fun `lw wins over the class fallback when the way has no lanes tag`() {
        // primary falls back to 3 lanes; the piece says 2.2
        val want = dark.width("carriageway", StyleProbe.road(2), z) * 1.1
        assertEquals(want, dark.width("carriageway", piece(null, 2.2), z), 1e-6)
    }

    @Test
    fun `a way without lw is drawn exactly as before`() {
        for (n in 1..7) {
            val f = StyleProbe.road(n)
            assertEquals(dark.width("carriageway", f, z) / n,
                dark.width("carriageway", StyleProbe.road(1), z), 1e-6)
        }
    }

    @Test
    fun `ground and ring carriageways end round, bridges end square`() {
        val round = listOf(
            "carriageway-skirt", "carriageway-circular-skirt",
            "carriageway-casing", "carriageway-circular-casing",
            "carriageway", "carriageway-circular",
        )
        val butt = listOf("carriageway-bridge-skirt", "carriageway-bridge-casing", "carriageway-bridge")
        for (id in round) assertEquals(id, "round", dark.layout(id, "line-cap"))
        for (id in butt) assertEquals(id, "butt", dark.layout(id, "line-cap"))
    }

    @Test
    fun `no carriageway layer draws a taper carrier, and the road label still does`() {
        val carrier = StyleProbe.road(3) + ("taper_carrier" to true) + ("name" to "Al Rayyan Rd")
        val carriageway = dark.ids.filter { it.startsWith("carriageway") }
        assertEquals(9, carriageway.size)
        for (id in carriageway) {
            assertEquals(id, false, dark.drawn(id, carrier, z))
            assertEquals(id, true, dark.drawn(id, StyleProbe.road(3), z) ||
                dark.drawn(id, StyleProbe.road(3, bridge = "yes"), z) ||
                dark.drawn(id, StyleProbe.road(3) + ("junction" to "roundabout"), z))
        }
        assertEquals(true, dark.drawn("road-labels", carrier, z))
        // the body and parts carry no name, so they are never labelled
        assertEquals(false, dark.drawn("road-labels", piece(3, 2.5), z))
    }

    @Test
    fun `both themes carry the same caps and the same lw reading`() {
        val light = StyleProbe(VectorStyle.MapTheme.LIGHT)
        for (id in groundWidths + bridgeWidths) {
            assertEquals(id, dark.layout(id, "line-cap"), light.layout(id, "line-cap"))
            assertEquals(id, dark.width(id, piece(3, 2.5), z), light.width(id, piece(3, 2.5), z), 1e-6)
        }
    }
}
