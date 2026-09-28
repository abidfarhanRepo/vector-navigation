package dev.vector.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What `/tiles/version` says, and what the app makes of it.
 *
 * The response shape is Commit 4's, and every case below is one the app will
 * genuinely meet:
 *
 *   * a release-aware server on a release directory — the target state;
 *   * a release-aware server on the **un-migrated volume production runs
 *     today**, which reports `release` present and empty;
 *   * a server predating the contract, which omits the field entirely.
 *
 * The last two must both keep working, because the `TILE_DIR` migration is
 * deliberately deferred and an APK outlives a deployment.
 */
class TileReleaseParseTest {

    /**
     * The parse under test, mirroring `VectorApi.tileSetOrNull`.
     *
     * The network half of that function needs a socket; this is the half that
     * can be wrong in a way a driver would see. `stringOrNull` is the
     * production helper itself, not a copy, so the `org.json` null trap it
     * exists for is genuinely exercised.
     */
    private fun parse(json: String): VectorApi.TileSet {
        val api = VectorApi(base = "http://unused", token = "")
        val j = JSONObject(json)
        return with(api) {
            VectorApi.TileSet(
                j.optLong("epoch", 0L),
                j.optInt("minzoom", 11),
                j.optInt("maxzoom", 13),
                j.stringOrNull("release") ?: "",
            )
        }
    }

    @Test
    fun `a release-aware server names its release`() {
        val t = parse(
            """{"release":"vector-tiles-2026-09-19T2004Z-4d627cd","epoch":1789000000,
               "source":"manifest","minzoom":11,"maxzoom":15}""")

        assertEquals("vector-tiles-2026-09-19T2004Z-4d627cd", t.release)
        assertEquals("vector-tiles-2026-09-19T2004Z-4d627cd", t.token)
        assertEquals(1789000000L, t.epoch)
        assertEquals(11, t.minZoom)
        assertEquals(15, t.maxZoom)
    }

    @Test
    fun `an un-migrated volume reports an empty release and is read by epoch`() {
        val t = parse(
            """{"release":"","epoch":1789000000,"source":"mtime",
               "minzoom":11,"maxzoom":13}""")

        assertEquals("", t.release)
        assertEquals("1789000000", t.token)
    }

    @Test
    fun `a server too old to know about releases still works`() {
        val t = parse("""{"epoch":1789000000,"minzoom":11,"maxzoom":13}""")

        assertEquals("", t.release)
        assertEquals("1789000000", t.token)
        assertEquals(11, t.minZoom)
        assertEquals(13, t.maxZoom)
    }

    @Test
    fun `a JSON null release is empty, not the word null`() {
        /**
         * `optString(key, "")` returns the four-character string **"null"**
         * for a JSON null rather than the fallback. It is documented `org.json`
         * behaviour and this codebase already met it once in V4, where it
         * rendered "null · Ibn Katheer Street" on an S24.
         *
         * Here it would be worse than cosmetic. "null" is a perfectly usable
         * cache key, so every tile URL would carry `?v=null`, and two
         * genuinely different releases would share one URL space — the single
         * condition under which AC-19's measurement of the ambient cache stops
         * holding, and the cache starts serving one release's bytes for
         * another. Correctly, per HTTP. Wrongly, per product.
         */
        val t = parse("""{"release":null,"epoch":1789000000}""")

        assertEquals("", t.release)
        assertEquals("1789000000", t.token)
        assertTrue("a null release must never reach a URL", t.token != "null")
    }

    @Test
    fun `a missing zoom range falls back conservatively, as before`() {
        val t = parse("""{"release":"vector-tiles-2026-09-19T2004Z-4d627cd"}""")

        assertEquals(11, t.minZoom)
        assertEquals(13, t.maxZoom)
        assertEquals(0L, t.epoch)
    }

    @Test
    fun `the token prefers the release over the epoch`() {
        val t = VectorApi.TileSet(1789000000L, 11, 15, "vector-tiles-2026-09-19T2004Z-4d627cd")
        assertEquals("vector-tiles-2026-09-19T2004Z-4d627cd", t.token)
    }

    @Test
    fun `the default TileSet is unchanged for every existing caller`() {
        /**
         * `release` was added with a default so the cold-start fallback and
         * every existing construction mean exactly what they meant before.
         */
        val t = VectorApi.TileSet(0L, 11, 13)
        assertEquals("", t.release)
        assertEquals("0", t.token)
    }
}
