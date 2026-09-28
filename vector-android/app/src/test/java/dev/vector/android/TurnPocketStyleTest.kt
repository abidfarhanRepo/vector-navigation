package dev.vector.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turn pockets are drawn as part of the road, not as a road of their own.
 *
 * Field report 2026-09-24, C Ring × Rawdat Al Khail: a "random highway in the
 * middle from the intersection". It was the junction's triple left/U-turn
 * pockets — `trunk_link`, `lanes=3`, `turn:lanes=reverse;left|left|left`,
 * 243–370 m long, mapped down the median — each drawn with a full deck AND its
 * own kerb, so the median read as a third highway and the pocket kerbs showed
 * through the surface as white lines. Reproduced on MapLibre Native (emulator)
 * and GL JS over the production tiles.
 *
 * What these pin: a pocket keeps its deck at its true width, loses the kerb
 * casing and the skirt (the two things that draw an edge), and an ordinary
 * link — one with any through, unmarked, `none` or merge lane — is untouched.
 */
class TurnPocketStyleTest {

    private val themes = listOf(StyleProbe(VectorStyle.MapTheme.DARK), StyleProbe(VectorStyle.MapTheme.LIGHT))
    private val z = 18.0

    private fun link(turn: String?, highway: String = "trunk_link", lanes: Int = 3, bridge: String? = null) =
        StyleProbe.road(lanes, highway, bridge) + (if (turn != null) mapOf("turn:lanes" to turn) else emptyMap())

    /** The exact C Ring pocket, and the other all-turn shapes in the same tile. */
    private val pockets = listOf(
        link("reverse;left|left|left"),
        link("left|left", lanes = 2),
        link("reverse", lanes = 1),
        link("right", highway = "primary_link", lanes = 1),
        link("slight_left|left", highway = "secondary_link", lanes = 2),
    )

    /** Links that carry a through, unmarked, `none` or merge lane, or no turn tag at all. */
    private val ordinary = listOf(
        link(null),
        link("left|through|through"),
        link("left||"),
        link("|left", lanes = 2),
        link("left|", lanes = 2),
        link("left||right"),
        link("none|left", lanes = 2),
        link("left|merge_to_right", lanes = 2),
    )

    private val edges = listOf("carriageway-casing", "carriageway-skirt")

    @Test
    fun `a turn pocket has no kerb and no skirt, so it is not drawn as a separate road`() {
        for (p in themes) for (f in pockets) for (id in edges) {
            assertFalse("$id must not draw pocket ${f["turn:lanes"]}", p.drawn(id, f, z))
        }
    }

    @Test
    fun `a turn pocket keeps its deck at its true width, so it fuses with the carriageway`() {
        for (p in themes) for (f in pockets) {
            assertTrue("deck must draw pocket ${f["turn:lanes"]}", p.drawn("carriageway", f, z))
            val plain = StyleProbe.road(f["lanes"].toString().toInt(), f["highway"] as String)
            assertEquals("pocket deck width", p.width("carriageway", plain, z), p.width("carriageway", f, z), 1e-9)
        }
    }

    @Test
    fun `an ordinary link keeps its kerb and skirt`() {
        for (p in themes) for (f in ordinary) for (id in edges) {
            assertTrue("$id must still draw link ${f["turn:lanes"]}", p.drawn(id, f, z))
        }
    }

    @Test
    fun `a main carriageway is never a pocket, even with turn lanes`() {
        for (p in themes) for (id in edges) {
            val main = StyleProbe.road(4, "trunk") + ("turn:lanes" to "left|left|left|left")
            assertTrue("$id must draw a trunk with turn:lanes", p.drawn(id, main, z))
        }
    }

    @Test
    fun `a pocket on a bridge has no bridge kerb either`() {
        for (p in themes) {
            val f = link("left|left", lanes = 2, bridge = "yes")
            assertTrue(p.drawn("carriageway-bridge", f, z))
            assertFalse(p.drawn("carriageway-bridge-casing", f, z))
            assertFalse(p.drawn("carriageway-bridge-skirt", f, z))
            assertTrue(p.drawn("carriageway-bridge-casing", link("left|through", bridge = "yes"), z))
        }
    }

    @Test
    fun `the class-ramp link casing skips pockets too`() {
        for (p in themes) {
            assertFalse(p.drawn("roads-links-casing", link("reverse;left|left|left"), 14.0))
            assertTrue(p.drawn("roads-links-casing", link("left|through"), 14.0))
        }
    }
}
