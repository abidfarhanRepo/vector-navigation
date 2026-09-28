package dev.vector.android

import android.util.DisplayMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The warning pill's bitmap, and the three numbers that have to agree about it.
 *
 * ## Why this file exists
 *
 * MapLibre registers images through JNI and validates them there, so getting
 * the geometry wrong is not a wrong-looking pill — it is a **hard crash during
 * style load**, which happens at launch and again on every theme switch. Two of
 * them arrived within ten minutes of each other on the emulator and neither
 * could have been caught by anything else in the suite:
 *
 *  - `ArrayIndexOutOfBoundsException: float[] offset=0 length=1 src.length=0`,
 *    from passing an EMPTY stretch list for the vertical axis. The JNI reads
 *    one float pair per axis and does not check that there is one.
 *  - `java.lang.Error: content area is invalid`, from a content box computed
 *    as `2 * r` in floating point against a bitmap sized `(2 * r).toInt()` —
 *    a quarter of a pixel taller than the image it described.
 *
 * Both are arithmetic, both are checkable here, and neither is visible in a
 * style document. The rule is simply that the bands and the box must lie inside
 * the bitmap that `pill` actually produces — which is why `pillBox` is the one
 * place any of them is computed.
 *
 * Densities from 1x to 4x, because the bug that shipped was a rounding
 * difference and rounding differences do not reproduce at every scale.
 */
@RunWith(RobolectricTestRunner::class)
// NATIVE, so `Canvas` actually rasterises. Robolectric's legacy graphics mode
// returns a bitmap of the right size with nothing drawn into it, which would
// let `the pill is drawn, not left transparent` pass on an empty image — the
// precise failure it exists to catch.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PillImageTest {

    private fun metrics(d: Float) = DisplayMetrics().apply { density = d }

    private val densities = listOf(1.0f, 1.5f, 2.0f, 2.625f, 3.0f, 3.5f, 4.0f)

    @Test
    fun `the stretch bands lie inside the bitmap at every density`() {
        for (d in densities) {
            val m = metrics(d)
            val bmp = VectorMarkers.pill(m, 0xFF141A24.toInt(), 0xFF8FA1B8.toInt())
            val (lo, hi) = VectorMarkers.pillStretch(m)
            assertTrue("${d}x: the stretch band starts at $lo", lo >= 0f)
            assertTrue("${d}x: the band is empty ($lo..$hi)", hi > lo)
            assertTrue("${d}x: the band ends at $hi, past a ${bmp.width} px image",
                       hi <= bmp.width.toFloat())
            assertTrue("${d}x: the band ends at $hi, past a ${bmp.height} px image",
                       hi <= bmp.height.toFloat())
        }
    }

    @Test
    fun `the content box lies inside the bitmap at every density`() {
        for (d in densities) {
            val m = metrics(d)
            val bmp = VectorMarkers.pill(m, 0xFF141A24.toInt(), 0xFF8FA1B8.toInt())
            val (l, t, r, b) = VectorMarkers.pillContent(m).let {
                listOf(it[0], it[1], it[2], it[3])
            }
            assertTrue("${d}x: content left $l", l >= 0f)
            assertTrue("${d}x: content top $t", t >= 0f)
            assertTrue("${d}x: content is inside out ($l..$r)", r > l)
            assertTrue("${d}x: content is inside out ($t..$b)", b > t)
            assertTrue("${d}x: content right $r past a ${bmp.width} px image",
                       r <= bmp.width.toFloat())
            assertTrue("${d}x: content bottom $b past a ${bmp.height} px image",
                       b <= bmp.height.toFloat())
        }
    }

    @Test
    fun `the content box is where the words go, not the two pixels that repeat`() {
        // The mistake that produced pills three hundred pixels wide with "Left"
        // floating in the middle: the content box was set to the stretch band,
        // so MapLibre had to fit the label into two pixels and inflated the
        // capsule around it. The box has to be most of the bitmap.
        for (d in densities) {
            val m = metrics(d)
            val bmp = VectorMarkers.pill(m, 0xFF141A24.toInt(), 0xFF8FA1B8.toInt())
            val box = VectorMarkers.pillContent(m)
            val (lo, hi) = VectorMarkers.pillStretch(m)
            val boxWidth = box[2] - box[0]
            assertTrue(
                "${d}x: the content box is ${boxWidth} px of a ${bmp.width} px pill — " +
                    "the capsule will be inflated to fit the text into it",
                boxWidth > bmp.width * 0.5f,
            )
            assertTrue("${d}x: the content box IS the stretch band",
                       boxWidth > (hi - lo) * 2)
            // And it stays off the rounded ends, so text never rides the curve.
            assertTrue("${d}x: the content box reaches the corner", box[0] > 0f)
        }
    }

    @Test
    fun `the pill is a capsule, wider than it is tall and tall enough to read`() {
        for (d in densities) {
            val m = metrics(d)
            val bmp = VectorMarkers.pill(m, 0xFF141A24.toInt(), 0xFF8FA1B8.toInt())
            assertTrue("${d}x: the pill is ${bmp.width}x${bmp.height}", bmp.width > bmp.height)
            // The whole point of stretching is that the bitmap stays small; a
            // capsule the width of the longest label would defeat it.
            assertTrue("${d}x: the pill bitmap is ${bmp.width} px wide before stretching",
                       bmp.width < bmp.height * 2)
            assertEquals(
                "${d}x: the pill is not ${2 * VectorMarkers.PILL_RADIUS_DP} dp tall",
                2 * VectorMarkers.PILL_RADIUS_DP * d, bmp.height.toFloat(), 1.5f,
            )
        }
    }

    @Test
    fun `the pill is drawn, not left transparent`() {
        // A registered image that is entirely transparent renders nothing and
        // reports nothing, which on a symbol layer looks exactly like the
        // feature not being there.
        val m = metrics(2.625f)
        val bmp = VectorMarkers.pill(m, 0xFF141A24.toInt(), 0xFF8FA1B8.toInt())
        assertEquals("the pill's middle is transparent",
                     0xFF141A24.toInt(), bmp.getPixel(bmp.width / 2, bmp.height / 2))
        // The corners are outside the capsule and must stay clear, or the pill
        // is a rectangle.
        assertEquals("the pill has square corners", 0, bmp.getPixel(0, 0))
        assertEquals(0, bmp.getPixel(bmp.width - 1, bmp.height - 1))
    }

    @Test
    fun `a pill and its geometry are the same at one density as at another`() {
        // Proportions, not pixels: the pill has to be the same physical size on
        // a 1x tablet and a 4x phone, which is the property `VectorMarkers`
        // states for every other marker in the file.
        val ratios = densities.map { d ->
            val bmp = VectorMarkers.pill(metrics(d), 0xFF141A24.toInt(), 0xFF8FA1B8.toInt())
            bmp.width.toFloat() / bmp.height
        }
        for (r in ratios) assertEquals("the pill's shape moves with the density",
                                       ratios.first(), r, 0.12f)
    }
}
