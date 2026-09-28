package dev.vector.android

import android.util.DisplayMetrics
import dev.vector.geo.Callouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The warning glyphs: that they exist, that they are drawn, and that they are
 * three different pictures.
 *
 * ## Why this is a test and not a screenshot
 *
 * The style draws a callout's glyph with `["get", "icon"]`, and MapLibre
 * renders NOTHING for an `icon-image` that names an unregistered image — no
 * warning, no log line, no error. So the failure mode this file guards is a
 * warning that has a paragraph of copy, an entry in the vocabulary, a layer in
 * the style, and no picture: visible only by looking at the screen, and only
 * for the one callout kind whose id drifted.
 *
 * The last assertion is the one worth keeping when everything else is green: a
 * signal glyph and a camera glyph that rasterise to the same pixels would pass
 * every "is it drawn" check while making the icons pointless, which is the
 * whole reason they were added.
 */
@RunWith(RobolectricTestRunner::class)
// NATIVE, so `Canvas` actually rasterises — the legacy mode returns a correctly
// sized bitmap with nothing drawn in it, which would let "is it drawn" pass on
// an empty image.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WarnGlyphTest {

    private fun metrics(d: Float) = DisplayMetrics().apply { density = d }

    private fun images(d: Float = 2.75f) = VectorMarkers.warningImages(metrics(d))

    @Test
    fun `every value in the callout vocabulary has a registered image`() {
        // The contract across the module boundary: `Callouts.Icon.ALL` is what
        // the wire can carry, `WARN_IMAGES` is what the renderer registers.
        assertEquals(Callouts.Icon.ALL, VectorMarkers.WARN_IMAGES)
        assertEquals(Callouts.Icon.ALL, images().map { it.first })
    }

    @Test
    fun `each glyph is drawn, not left transparent`() {
        for ((id, bmp) in images()) {
            var ink = 0
            for (y in 0 until bmp.height) {
                for (x in 0 until bmp.width) {
                    if (android.graphics.Color.alpha(bmp.getPixel(x, y)) > 0) ink++
                }
            }
            // A glyph that is mostly empty is a glyph that will not be seen;
            // the shapes below are solid, so this is a floor and not a target.
            assertTrue("$id has only $ink inked pixels", ink > bmp.width * bmp.height / 20)
        }
    }

    @Test
    fun `the glyphs are physically sized, and square`() {
        // Density-independent: the same physical size on a 1x panel and a 4x one.
        for (d in listOf(1f, 2f, 2.75f, 4f)) {
            for ((_, bmp) in images(d)) {
                assertEquals(bmp.width, bmp.height)
                assertTrue("density $d produced ${bmp.width}px", bmp.width >= 16)
            }
        }
    }

    @Test
    fun `a signal and a camera are different pictures`() {
        val byId = images().toMap()
        val signal = byId.getValue(Callouts.Icon.SIGNAL)
        val speed = byId.getValue(Callouts.Icon.CAMERA_SPEED)
        assertNotEquals(signature(signal), signature(speed))
    }

    @Test
    fun `a red-light camera is distinguishable from a fixed speed camera`() {
        // Same housing, different lens. The distinction is what a driver acts
        // on — one watches the signal, the other watches the road — so the two
        // must not rasterise identically.
        val byId = images().toMap()
        assertNotEquals(
            signature(byId.getValue(Callouts.Icon.CAMERA_SPEED)),
            signature(byId.getValue(Callouts.Icon.CAMERA_RED_LIGHT)),
        )
    }

    /**
     * A content fingerprint over EVERY pixel.
     *
     * Not a sparse grid: the first version sampled 16 points on a 4x4 lattice
     * and passed two glyphs whose only difference was the aperture, which is
     * exactly the difference the icon exists to show. A test that cannot see the
     * distinction cannot defend it.
     */
    private fun signature(bmp: android.graphics.Bitmap): Int {
        var h = 17
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                h = h * 31 + bmp.getPixel(x, y)
            }
        }
        return h
    }
}
