package dev.vector.android

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a dropped pin is called.
 *
 * Every case below is a coordinate measured against production `/reverse` on
 * 2026-09-21 — the distances are the real ones the endpoint ranked on. The
 * defect these pin was reported from the S24 as *"I cannot drop a pin in a
 * random address, instead it just selects the nearest POI"*, and the old code
 * (`features[0].name`, unconditionally) failed all three of them.
 */
class PinNameTest {

    private val fallback = "Dropped pin"

    private fun hit(name: String, kind: String, m: Double) =
        VectorApi.ReverseHit(name, kind, m)

    @Test
    fun `a pin on a street is named after the street, not the shop beside it`() {
        // 25.2720,51.5330 — Mansoura. This is the reported defect exactly: the
        // salon is nearer, and it is still the wrong answer.
        val hits = listOf(
            hit("Princess Ladies Saloon", "poi", 13.5),
            hit("Canara Jewellery", "poi", 21.8),
            hit("ابن درهم", "road", 27.7),
        )
        assertEquals("ابن درهم", PinName.choose(hits, fallback))
    }

    @Test
    fun `a pin in open country keeps the placeholder rather than a village 3_5 km away`() {
        val hits = listOf(
            hit("Umm Hotta", "label", 3_528.3),
            hit("أم حوطة", "label", 6_916.7),
            hit("قاعدة أم حوطة", "park", 7_304.1),
        )
        assertEquals(fallback, PinName.choose(hits, fallback))
    }

    @Test
    fun `a named building node is not a name for the pin`() {
        // 25.2560,51.4460 — Al Waab. 17.3 m away, and still not a place: the
        // extract promotes every named building to a POI, so this is somebody's
        // name tag. The road 45.1 m off is the honest answer.
        val hits = listOf(
            hit("mohamed abdullah", "poi", 17.3),
            hit("اسماء", "road", 45.1),
            hit("Uterque", "poi", 77.5),
        )
        assertEquals("اسماء", PinName.choose(hits, fallback))
    }

    @Test
    fun `the road bound is inclusive and the far side of it is not`() {
        assertEquals(
            "Al Aasha Street",
            PinName.choose(listOf(hit("Al Aasha Street", "road", PinName.ROAD_MAX_M)), fallback),
        )
        assertEquals(
            fallback,
            PinName.choose(
                listOf(hit("Al Aasha Street", "road", PinName.ROAD_MAX_M + 0.1)),
                fallback,
            ),
        )
    }

    @Test
    fun `a POI just outside the near bound does not name the pin`() {
        // 60 m is the whole difference between "there is something right here"
        // and "a shop somewhere down the road".
        assertEquals(
            "Loyal City market",
            PinName.choose(listOf(hit("Loyal City market", "poi", PinName.NEAR_MAX_M)), fallback),
        )
        assertEquals(
            fallback,
            PinName.choose(
                listOf(hit("Loyal City market", "poi", PinName.NEAR_MAX_M + 0.1)),
                fallback,
            ),
        )
    }

    @Test
    fun `a road beyond the road bound still beats nothing, but a POI inside the near bound beats it`() {
        val hits = listOf(
            hit("Al Matar Street", "road", 400.0),
            hit("Chubb Fire Qatar", "poi", 17.3),
        )
        assertEquals("Chubb Fire Qatar", PinName.choose(hits, fallback))
    }

    @Test
    fun `the nearest road wins even when it is not the first hit`() {
        val hits = listOf(
            hit("Zig Zat Express", "poi", 12.6),
            hit("حيان", "road", 26.5),
            hit("Rawdat Al Khail Street", "road", 88.0),
        )
        assertEquals("حيان", PinName.choose(hits, fallback))
    }

    @Test
    fun `blank and non-finite hits are ignored rather than shown`() {
        val hits = listOf(
            hit("   ", "road", 5.0),
            hit("Chubb Fire Qatar", "poi", Double.NaN),
        )
        assertEquals(fallback, PinName.choose(hits, fallback))
    }

    @Test
    fun `no hits at all keeps the placeholder`() {
        assertEquals(fallback, PinName.choose(emptyList(), fallback))
    }
}
