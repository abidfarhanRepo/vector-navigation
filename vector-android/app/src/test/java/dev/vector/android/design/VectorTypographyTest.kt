package dev.vector.android.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The type scale, as behaviour rather than as a list of numbers.
 *
 * ## Why a test and not a table in a document
 *
 * Every property asserted here is one the brief states as a rule — the size
 * bands, the driving legibility floor, the single sanctioned all-caps role —
 * and every one of them is the kind of rule that survives exactly as long as
 * somebody re-reads the document. A scale is nine numbers away from being
 * wrong in a way nobody notices: `cardTitle` a size up still looks like a card
 * title, and `hudPrimary` at 26 sp still looks like a HUD until it is read at
 * 100 km/h.
 *
 * ## What "behaviour" means for a [TextStyle]
 *
 * A `TextStyle` has no runtime behaviour, so the observable facts are the ones
 * the renderer acts on: the resolved size, the leading, the weight, the
 * tracking, and — most of all — *which family the role is set in*, because that
 * is the difference the audit paid for (Manrope for display and the driving
 * HUD, Inter for everything that has to hold up at 2.0× text size). Asserting
 * `fontWeight == ExtraBold` on `hudPrimary` says nothing on its own; asserting
 * that the family *has* an ExtraBold is the half that a wrong extra weight
 * actually breaks, because Compose silently substitutes the nearest installed
 * weight for one a family does not carry.
 *
 * ## The dark theme is the same scale
 *
 * Asserted, not assumed: type is not a theme property, and a dark scale that
 * grew its own sizes is the bug the last test below catches.
 */
class VectorTypographyTest {

    /** A role, addressable by name so a failure can print which one it was. */
    private class Role(val name: String, val pick: (VectorTypography) -> TextStyle)

    private fun role(name: String, pick: (VectorTypography) -> TextStyle) = Role(name, pick)

    private val display = role("display") { it.display }
    private val screenTitle = role("screenTitle") { it.screenTitle }
    private val sectionTitle = role("sectionTitle") { it.sectionTitle }
    private val cardTitle = role("cardTitle") { it.cardTitle }
    private val body = role("body") { it.body }
    private val bodyStrong = role("bodyStrong") { it.bodyStrong }
    private val metadata = role("metadata") { it.metadata }
    private val chip = role("chip") { it.chip }
    private val caption = role("caption") { it.caption }
    private val button = role("button") { it.button }
    private val eyebrow = role("eyebrow") { it.eyebrow }
    private val hudPrimary = role("hudPrimary") { it.hudPrimary }
    private val hudSecondary = role("hudSecondary") { it.hudSecondary }
    private val hudContext = role("hudContext") { it.hudContext }
    private val hudSupporting = role("hudSupporting") { it.hudSupporting }
    private val dialNumber = role("dialNumber") { it.dialNumber }
    private val dialUnit = role("dialUnit") { it.dialUnit }

    /** Every role in the scale. A role missing from here is a role not checked. */
    private val roles = listOf(
        display, screenTitle, sectionTitle, cardTitle, body, bodyStrong, metadata,
        chip, caption, button, eyebrow,
        hudPrimary, hudSecondary, hudContext, hudSupporting, dialNumber, dialUnit,
    )

    /**
     * The names [VectorTypography] declares, read back from the class itself.
     *
     * Not a second copy of the list: the point is that this test cannot silently
     * stop covering a role. The scale is still growing, and a role added to the
     * data class without a line in the table below would otherwise be a role
     * whose leading, family and weight nobody checks.
     */
    private val declaredRoles: Set<String> = VectorTypography::class.java.methods
        .filter {
            it.parameterCount == 0 && it.returnType == TextStyle::class.java && it.name.startsWith("get")
        }
        .map { it.name.removePrefix("get").replaceFirstChar { c -> c.lowercase() } }
        .toSet()

    @Test
    fun `the table below covers every role the scale declares`() {
        assertEquals(
            "roles declared by VectorTypography that this test does not check",
            declaredRoles,
            roles.map { it.name }.toSet(),
        )
    }

    private val scales = listOf("light" to LightTypography, "dark" to DarkTypography)

    /**
     * Text that is drawn as tall as its own size collides with the line above it.
     *
     * The bug this catches: a role whose leading was set to its size (or below
     * it) while being added — a two-line place row that overlaps itself, which
     * is invisible in a single-line screenshot and obvious in a list.
     */
    @Test
    fun `every role's line is taller than its text`() {
        for ((theme, t) in scales) {
            for (r in roles) {
                val s = r.pick(t)
                assertTrue(
                    "$theme ${r.name}: font ${s.fontSize} in a ${s.lineHeight} line",
                    s.fontSize.isSp && s.lineHeight.isSp && s.lineHeight.value > s.fontSize.value,
                )
            }
        }
    }

    /**
     * The brief's bands, per role.
     *
     * The bug this catches: a role moved out of its band during a re-tune — the
     * "make the card title a bit bigger" edit that turns a card title into a
     * section title and collapses the hierarchy the bands exist to protect.
     */
    private val bands = listOf(
        screenTitle to (28f..32f),
        sectionTitle to (20f..24f),
        cardTitle to (16f..18f),
        body to (14f..16f),
        metadata to (12f..14f),
        chip to (12f..14f),
        button to (15f..16f),
    )

    @Test
    fun `every role sits in its size band`() {
        for ((r, band) in bands) {
            val size = r.pick(LightTypography).fontSize.value
            assertTrue(
                "${r.name} is ${size}sp; the brief's band is ${band.start}–${band.endInclusive}sp",
                size in band,
            )
        }
    }

    /**
     * The hierarchy has a top, and the driving role has a floor.
     *
     * The bug this catches: a new hero role that out-sizes `display` and makes
     * onboarding inconsistent with every other screen — or `hudPrimary` slipping
     * below 30 sp, which is the measured value the audit took from the
     * reference's HUD and the one number here that is read at 100 km/h.
     */
    @Test
    fun `display is the largest role and the hud clears its floor`() {
        val sizes = roles.map { it to it.pick(LightTypography).fontSize.value }
        val biggest = sizes.maxBy { it.second }
        assertEquals(
            "display is ${display.pick(LightTypography).fontSize}, the largest role is " +
                "${biggest.first.name} at ${biggest.second}sp",
            "display",
            biggest.first.name,
        )
        assertTrue(
            "hudPrimary is ${hudPrimary.pick(LightTypography).fontSize}; the driving floor is 30sp",
            hudPrimary.pick(LightTypography).fontSize.value >= 30f,
        )
    }

    /**
     * One tracked label role, and it is the caps one.
     *
     * All caps is the brief's single sanctioned exception, and the system
     * encodes it as tracking rather than as a font feature: [eyebrow] is 11 sp
     * bold at +0.08 em, which is what makes `RECENT` read as a label instead of
     * as a shout. The scale ships two other 11 sp roles and the distinction
     * between them is deliberate — `caption` is a unit suffix and is *untracked*,
     * and `dialUnit` is tracked at +0.04 em, below the band.
     *
     * The bug this catches: a second caps label appearing by copy-and-paste
     * (`caption` given `eyebrow`'s tracking), or `eyebrow` losing its tracking
     * and becoming indistinguishable from `caption` in a section header.
     */
    @Test
    fun `exactly one role is a tracked label, and running text is not tracked`() {
        val tracked = roles.filter { it.pick(LightTypography).letterSpacing.value >= 0.05f }
        assertEquals(
            "roles tracked at or above +0.05 em: ${tracked.map { it.name }}",
            listOf("eyebrow"),
            tracked.map { it.name },
        )
        assertTrue(
            "caption is the same 11sp as eyebrow and must stay untracked; it is tracked at " +
                "${caption.pick(LightTypography).letterSpacing}",
            caption.pick(LightTypography).letterSpacing.value == 0f,
        )
        for (r in listOf(body, bodyStrong, metadata)) {
            val tracking = r.pick(LightTypography).letterSpacing.value
            assertTrue(
                "${r.name} is running copy and is tracked at ${tracking}em (±0.01 allowed)",
                tracking in -0.01f..0.01f,
            )
        }
    }

    /**
     * Two families, and every role in exactly one of them.
     *
     * The split is the audit's decision, not a detail: Manrope carries the
     * display sizes, the screen titles, the section titles and the whole driving
     * HUD; Inter carries everything read at length, because its numerals and its
     * lowercase `l`/`I`/`1` hold up at 2.0× text size.
     *
     * The bug this catches: a role wired to the wrong builder — `hudPrimary` in
     * Inter loses the tallest x-height in the system at exactly the size that
     * needs it, and a title in Inter reads as body copy one weight up. It also
     * catches a role that names no family at all, which silently falls back to
     * the platform font and reintroduces Roboto to the product.
     */
    @Test
    fun `the two families are distinct and every role is set in one of them`() {
        val displayFamily = VectorFonts.display
        val uiFamily = VectorFonts.ui
        assertNotSame("VectorFonts.display and VectorFonts.ui must be different families", displayFamily, uiFamily)

        val setInDisplay = listOf(display, screenTitle, sectionTitle, hudPrimary, hudSecondary, dialNumber)
        val wrong = roles.mapNotNull { r ->
            val expected = if (r in setInDisplay) displayFamily else uiFamily
            val actual = r.pick(LightTypography).fontFamily
            if (actual === expected) null
            else "${r.name} is set in ${if (actual === displayFamily) "display" else if (actual === uiFamily) "ui" else actual}"
        }
        assertTrue(
            "roles in the wrong family (expected Manrope for display/titles/HUD, Inter for the rest): " +
                wrong.joinToString(", "),
            wrong.isEmpty(),
        )
    }

    /**
     * The families carry the weights the scale asks of them.
     *
     * Compose does not fail on a weight a family does not have: it resolves the
     * nearest one, so `metadata` asked for `Medium` in a family with only 400
     * and 600 would render as one of those and nobody would see an error. This
     * is the assertion that makes `FontWeight.Medium` in the scale mean
     * something — the bug it catches is a role asking for a weight nobody
     * bundled, which changes how the text looks without changing a single line
     * of the file that defines it.
     */
    @Test
    fun `each family carries every weight the scale uses of it`() {
        val displayFamily = VectorFonts.display
        val uiFamily = VectorFonts.ui
        for ((family, name) in listOf(displayFamily to "display", uiFamily to "ui")) {
            val installed = installedWeights(family)
            val asked = roles
                .filter { it.pick(LightTypography).fontFamily === family }
                .map { it.pick(LightTypography).fontWeight }
                .distinct()
            val missing = asked.filter { it !in installed }
            assertTrue(
                "VectorFonts.$name ships ${installed.joinToString(", ")} but the scale asks for " +
                    missing.joinToString(", "),
                missing.isEmpty(),
            )
        }
    }

    /**
     * Type is not a theme property.
     *
     * `DarkTypography` is an alias of the light scale, and the value of that
     * decision is that a driver who switches theme at dusk sees the same
     * document re-inked, not re-typeset. The bug this catches: a dark scale
     * that grows its own copy with a size or a weight "fixed" in it, so the two
     * themes drift apart one role at a time.
     */
    @Test
    fun `the dark scale is the same scale`() {
        for (r in roles) {
            assertEquals(
                "${r.name} differs between the light and dark scales",
                r.pick(LightTypography),
                r.pick(DarkTypography),
            )
        }
    }

    /** The weights a [FontFamily] actually installs, in the order it declares them. */
    private fun installedWeights(family: FontFamily): List<FontWeight> {
        // `FontListFontFamily` is a `List<Font>`; that is the only public way to
        // read back what a family was built with, and it is the same list the
        // resolver chooses from at render time.
        val fonts = family as? List<Font>
            ?: error("${VectorFonts::class.simpleName} family $family is not a font list")
        return fonts.map { it.weight }
    }
}
