package dev.vector.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `?v=` token in the tile URL — what AC-19 measured it actually does.
 *
 * The server **strips the query before resolving a path**, verified on
 * production: `?v=1` and `?v=2` return byte-identical responses. So this
 * parameter routes nothing, and no part of the system may ever come to depend
 * on it doing so.
 *
 * What it does instead is give each release its own URL space in MapLibre's
 * ambient cache. AC-19 measured that cache correct: two real releases coexisted
 * in it — 1,380,352 bytes holding both, 716,800 holding one — precisely because
 * their URLs differed, and a rollback A→B→A returned to A's tiles still
 * resident, making a rollback *cheaper* than a fresh install.
 *
 * That result holds under exactly one condition, and it is the condition these
 * tests defend: **distinct releases must never share a token.** If two tile
 * sets ever present the same `?v=`, the cache serves the first one's bytes for
 * the second — correctly per HTTP, wrongly per product, and invisibly on a
 * driver's phone.
 */
class TileUrlContractTest {

    private fun tileUrl(doc: JSONObject): String =
        doc.getJSONObject("sources").getJSONObject("vector")
            .getJSONArray("tiles").getString(0)

    private fun styleWith(epoch: Long, release: String): JSONObject =
        JSONObject(VectorStyle.json(
            "http://host:9003", epoch, 11, 15,
            VectorStyle.MapTheme.DARK, lang = null, extruded = false,
            release = release))

    @Test
    fun `a named release becomes the token`() {
        val url = tileUrl(styleWith(1789000000L, "vector-tiles-2026-09-19T2004Z-4d627cd"))

        assertTrue(url, url.endsWith("?v=vector-tiles-2026-09-19T2004Z-4d627cd"))
        assertTrue("the epoch must not also appear", !url.contains("1789000000"))
    }

    @Test
    fun `no release keeps the epoch, exactly as before V7_7`() {
        val url = tileUrl(styleWith(1789000000L, ""))

        assertTrue(url, url.endsWith("?v=1789000000"))
    }

    @Test
    fun `every existing positional caller is unaffected`() {
        /**
         * `release` was added LAST in the parameter list and defaulted, so
         * `VectorStyle.json(base, epoch, min, max, theme)` — the call shape
         * used by MainActivity before V7.7 and by four test files — still
         * means what it always meant.
         */
        val url = tileUrl(JSONObject(
            VectorStyle.json("http://host:9003", 42L, 11, 15,
                             VectorStyle.MapTheme.DARK)))

        assertTrue(url, url.endsWith("?v=42"))
    }

    @Test
    fun `two distinct releases never share a token`() {
        /**
         * The condition AC-19's cache result rests on, asserted directly.
         */
        val a = tileUrl(styleWith(1789000000L, "vector-tiles-2026-09-19T2004Z-4d627cd"))
        val b = tileUrl(styleWith(1789000000L, "vector-tiles-2026-09-19T2104Z-9757c54"))

        assertTrue("same epoch must not collapse two releases to one URL space", a != b)
    }

    @Test
    fun `the token needs no URL escaping`() {
        /**
         * A release id is `[a-z0-9-]` only, which is why it was chosen over a
         * content digest that would have needed a lookup table to read, and
         * over anything requiring percent-encoding. A token that had to be
         * escaped would be escaped in one place and not another sooner or
         * later, and the two spellings would partition the cache twice.
         */
        val id = "vector-tiles-2026-09-19T2004Z-4d627cd-dirty"
        val url = tileUrl(styleWith(0L, id))

        assertEquals("http://host:9003/tiles/{z}/{x}/{y}.mvt?v=$id", url)
        assertTrue(Regex("^[A-Za-z0-9.\\-_]+$").matches(id))
    }

    @Test
    fun `the style still declares the zoom range it was given`() {
        /**
         * Unchanged by V7.7 and pinned here because a release swap now changes
         * this range at runtime. MapLibre only overzooms ABOVE the declared
         * maximum: declaring 14 against a z11-13 bake made an S24 go black
         * past z13, and navigation sets the camera to 16.5.
         */
        val doc = styleWith(1L, "vector-tiles-2026-09-19T2004Z-4d627cd")
        val src = doc.getJSONObject("sources").getJSONObject("vector")

        assertEquals(11, src.getInt("minzoom"))
        assertEquals(15, src.getInt("maxzoom"))
    }
}
