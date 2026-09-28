package dev.vector.android.design

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The colours, measured: which one may sit on which.
 *
 * ## Why this file is the design system's real contract
 *
 * A palette is a set of values; a *system* is a set of promises about which
 * values may be combined. Nothing in [VectorColor.kt] enforces those promises —
 * `inkMuted` on `sunnyContainer` compiles, draws, and is unreadable — so the
 * only place the promise is kept is here. `VectorColor.kt` names this file when
 * it says that adding a role without adding its pair to this test is the one way
 * to get an inaccessible colour into the product.
 *
 * ## The bar, per pair rather than per role
 *
 * WCAG 2.1: **4.5:1** for body-size text, **3:1** for large text (≥ 24 sp
 * regular, or ≥ 18.66 sp bold) and for non-text UI. The bar belongs to the
 * *pair*, not to the colour, because the same role is large text in one place
 * (`ink` under the 34 sp `display`) and body text in another (13 sp metadata),
 * and because an icon does not have a size at all.
 *
 * Every ratio comes from [contrastRatio] in production code. This file never
 * re-implements the formula: a test that re-implements it passes while the
 * shipped maths drifts, which is the failure the file exists to prevent.
 *
 * ## What is deliberately NOT asserted
 *
 * Hairlines used as *dividers* (`border` between two list rows, the edge of a
 * card) are not held to 3:1. The reference audit records that decision — "cards
 * are a shade lighter than the page and separated by a hairline plus a soft
 * shadow rather than by contrast" (§2) — and two text rows separated by a
 * decorative rule do not fail 1.4.11, which is about information *required* to
 * identify a component or state.
 *
 * The same hairline used as the **only** boundary of an outlined control is a
 * different question, and is asserted separately below.
 *
 * ## Pairs that fail today
 *
 * The `VectorColor.kt` doc comment on the accent group says a fill carries
 * `onAccent` text, and the audit says a control's boundary must be visible.
 * Two groups below do not hold that line in the shipped palette. They are left
 * failing, with the measurement printed, rather than weakened — see the KDoc on
 * each for the role, the hexes and the ratio.
 */
class ContrastTest {

    /** A role, addressable by name so a failure can print which one it was. */
    private class Role(val name: String, val pick: (VectorColors) -> Color)

    private fun role(name: String, pick: (VectorColors) -> Color) = Role(name, pick)

    /** Reads as the thing being asserted: `ink on field`. */
    private infix fun Role.on(bg: Role) = this to bg

    // -- Fields and surfaces ------------------------------------------------
    private val field = role("field") { it.field }
    private val surface = role("surface") { it.surface }
    private val surfaceSunken = role("surfaceSunken") { it.surfaceSunken }
    private val surfaceFloating = role("surfaceFloating") { it.surfaceFloating }
    private val border = role("border") { it.border }
    private val controlBorder = role("controlBorder") { it.controlBorder }
    private val borderStrong = role("borderStrong") { it.borderStrong }

    // -- Text ---------------------------------------------------------------
    private val ink = role("ink") { it.ink }
    private val inkSecondary = role("inkSecondary") { it.inkSecondary }
    private val inkMuted = role("inkMuted") { it.inkMuted }
    private val inkOnSunken = role("inkOnSunken") { it.inkOnSunken }
    private val inkInverse = role("inkInverse") { it.inkInverse }

    // -- Brand --------------------------------------------------------------
    private val primary = role("primary") { it.primary }
    private val onPrimary = role("onPrimary") { it.onPrimary }
    private val primaryText = role("primaryText") { it.primaryText }
    private val primaryContainer = role("primaryContainer") { it.primaryContainer }
    private val onPrimaryContainer = role("onPrimaryContainer") { it.onPrimaryContainer }

    // -- Accents ------------------------------------------------------------
    private val coral = role("coral") { it.coral }
    private val sunny = role("sunny") { it.sunny }
    private val leaf = role("leaf") { it.leaf }
    private val lilac = role("lilac") { it.lilac }
    private val deepBlue = role("deepBlue") { it.deepBlue }
    private val onAccent = role("onAccent") { it.onAccent }

    // -- Status -------------------------------------------------------------
    private val success = role("success") { it.success }
    private val warning = role("warning") { it.warning }
    private val danger = role("danger") { it.danger }
    private val dangerText = role("dangerText") { it.dangerText }
    private val dangerContainer = role("dangerContainer") { it.dangerContainer }
    private val warningContainer = role("warningContainer") { it.warningContainer }

    // -- Guidance -----------------------------------------------------------
    private val guidanceSurface = role("guidanceSurface") { it.guidanceSurface }
    private val guidanceSurfaceSub = role("guidanceSurfaceSub") { it.guidanceSurfaceSub }
    private val onGuidance = role("onGuidance") { it.onGuidance }
    private val onGuidanceMuted = role("onGuidanceMuted") { it.onGuidanceMuted }
    private val guidanceAccent = role("guidanceAccent") { it.guidanceAccent }
    private val guidanceWarn = role("guidanceWarn") { it.guidanceWarn }
    private val guidanceDanger = role("guidanceDanger") { it.guidanceDanger }

    private val themes = listOf("light" to LightColors, "dark" to DarkColors)

    private fun Color.hex(): String =
        "#%02X%02X%02X".format((red * 255f).toInt(), (green * 255f).toInt(), (blue * 255f).toInt())

    /**
     * Every pair, in both themes, against one bar.
     *
     * All violations are collected and reported in a single message rather than
     * failing on the first: a palette change usually moves more than one pair,
     * and a message that prints one of four regressions costs a second run to
     * find the other three. The message carries the theme, the two role names,
     * both hex values and the measured ratio, so a failure is diagnosable from
     * the test output alone.
     */
    private fun assertContrast(label: String, min: Double, pairs: List<Pair<Role, Role>>) {
        val bad = mutableListOf<String>()
        for ((theme, c) in themes) {
            for ((fg, bg) in pairs) {
                val f = fg.pick(c)
                val b = bg.pick(c)
                val ratio = contrastRatio(f, b)
                if (ratio < min) {
                    bad += "%s: %-18s %s  on  %-18s %s  = %5.2f:1  (needs %.1f:1)"
                        .format(theme, fg.name, f.hex(), bg.name, b.hex(), ratio, min)
                }
            }
        }
        assertTrue(
            "$label — ${bad.size} of ${pairs.size * themes.size} checks below $min:1\n  " +
                bad.joinToString("\n  "),
            bad.isEmpty(),
        )
    }

    // -----------------------------------------------------------------------
    // Body-size text: 4.5:1
    // -----------------------------------------------------------------------

    /**
     * Running copy, on every surface it is set on.
     *
     * The bug this catches: a role that is legible everywhere except one
     * surface — `inkMuted` is 5.60:1 on `surface` and 4.74:1 on `surfaceSunken`,
     * so darkening or lightening either by a single step breaks the pair — or a
     * role copied from the ramp without being re-checked against the surface it
     * lands on.
     */
    private val bodyCopy = listOf(
        // Card titles, place rows, list copy: 15–17 sp.
        ink on field, ink on surface, ink on surfaceSunken, ink on surfaceFloating,
        // The second line of a two-line row — address, distance, unit: 13 sp.
        inkSecondary on field, inkSecondary on surface,
        inkSecondary on surfaceSunken, inkSecondary on surfaceFloating,
        // The quietest text the system ships: 13 sp metadata, eyebrow labels.
        inkMuted on field, inkMuted on surface,
        inkMuted on surfaceSunken, inkMuted on surfaceFloating,
        // Text on a well or a tinted container.
        inkOnSunken on field, inkOnSunken on surface,
        inkOnSunken on surfaceSunken, inkOnSunken on surfaceFloating,
        // The dark pill: VectorBadge's "948 saves" (default tint = ink).
        inkInverse on ink,
        // Brand-coloured text: the Ghost button label and section-header action.
        primaryText on field, primaryText on surface, primaryText on surfaceFloating,
        // The one primary CTA: 16 sp semibold on the brand fill.
        onPrimary on primary,
        // The tonal button and the active chip.
        onPrimaryContainer on primaryContainer,
        // The destructive row and the offline banner.
        dangerText on dangerContainer, warning on warningContainer,
        // The driving band: onGuidance is the road name, onGuidanceMuted the
        // supporting line, and the three accents are glyphs and status colour
        // that sit at body size next to them.
        onGuidance on guidanceSurface, onGuidanceMuted on guidanceSurface,
        guidanceAccent on guidanceSurface, guidanceWarn on guidanceSurface,
        guidanceDanger on guidanceSurface,
        onGuidance on guidanceSurfaceSub, onGuidanceMuted on guidanceSurfaceSub,
        guidanceAccent on guidanceSurfaceSub, guidanceWarn on guidanceSurfaceSub,
        guidanceDanger on guidanceSurfaceSub,
    )

    @Test
    fun `body size text meets 4_5 to 1 on every surface it ships on`() {
        assertContrast("body-size text", 4.5, bodyCopy)
    }

    // -----------------------------------------------------------------------
    // Large text: 3:1
    // -----------------------------------------------------------------------

    /**
     * The roles that are large enough for the lower bar, and nothing else.
     *
     * Qualified by size, from the scale: `display` (34 sp ExtraBold),
     * `screenTitle` (28 sp ExtraBold) and `hudPrimary` (31 sp ExtraBold) are
     * large by any reading; `sectionTitle` (21 sp Bold), `hudSecondary` (26 sp
     * Bold) and `dialNumber` (26 sp ExtraBold) clear the 18.66 sp bold line.
     * `cardTitle` (17 sp SemiBold), `hudContext` (18 sp SemiBold) and everything
     * below them do NOT, so their pairs are asserted at 4.5:1 above — the bug
     * this grouping catches is a role quietly moved onto the lenient bar.
     */
    private val largeText = listOf(
        ink on field, ink on surface,
        onGuidance on guidanceSurface, onGuidance on guidanceSurfaceSub,
    )

    @Test
    fun `large text meets 3 to 1`() {
        // These pairs clear 4.5:1 as well today. The 3:1 bar is what the size
        // of the role entitles them to; asserting the higher bar would fail on a
        // legitimate 34 sp display tone, and asserting nothing would let the
        // band drift into the background.
        assertContrast("large text", 3.0, largeText)
    }

    // -----------------------------------------------------------------------
    // Non-text UI: 3:1
    // -----------------------------------------------------------------------

    /**
     * Glyphs on the container they are drawn on.
     *
     * A glyph has no size, so 3:1 is the bar for all of them. The bug this
     * catches: a tint role swapped for one that vanishes — `onAccent` on a
     * `coral` fill is 5.08:1, but the same white on the `sunny` fill is the
     * failure asserted separately below, and `inkMuted` on a dark container
     * would be invisible at any size.
     */
    private val glyphs = listOf(
        onAccent on coral, onAccent on leaf, onAccent on lilac, onAccent on deepBlue,
        onAccent on primary, onAccent on success,
        onPrimary on primary, onPrimaryContainer on primaryContainer,
        ink on surfaceFloating, primaryText on surface,
        inkInverse on ink,
        guidanceAccent on guidanceSurface, guidanceWarn on guidanceSurface,
        guidanceDanger on guidanceSurface,
        guidanceAccent on guidanceSurfaceSub, guidanceWarn on guidanceSurfaceSub,
        guidanceDanger on guidanceSurfaceSub,
    )

    @Test
    fun `a glyph meets 3 to 1 against the container it is drawn on`() {
        assertContrast("glyphs", 3.0, glyphs)
    }

    /**
     * The boundary of a control that has nothing else to identify it.
     *
     * WCAG 1.4.11 asks for 3:1 on "visual information required to identify user
     * interface components". Three of Vector's controls are outlined and
     * nothing else:
     *
     *  - `VectorButton`'s Secondary variant is `fill = Color.Transparent` with a
     *    single `controlBorder` hairline and no shadow;
     *  - `VectorChip`'s rest state is `surfaceFloating` on a `surface` card —
     *    1.09:1 between the two fills — plus one `controlBorder` hairline;
     *  - `VectorIconButton`'s secondary variant is `surfaceFloating` with the
     *    same hairline and no elevation.
     *
     * So the outline is the whole of the control's visible boundary, and the bug
     * this catches is an outlined control whose edge is 1.2–1.7:1: it reads as a
     * floating label with no target in sunlight or at low vision, in both themes.
     *
     * ## Why this asserts `controlBorder` and not `border`
     *
     * Because they are two different jobs, and this test is what forced the
     * distinction to be made explicit. The first version of it asserted that
     * `border` reached 3:1, and every case failed at 1.21–1.79:1 — correctly, in
     * the sense that the assertion was right and the palette was wrong, but the
     * fix it implied was wrong too: darkening the *decorative* hairline until it
     * passed would have turned every card edge into a hard rule and destroyed the
     * surface language the reference audit measured (`~1.2:1` hairlines, cards
     * separated by softness rather than by contrast).
     *
     * The correct reading of WCAG 1.4.11 is that the requirement is about
     * *identifying a component*, so it binds the role that identifies one.
     * `controlBorder` is that role, `border` is not, and this test holds the line
     * between them.
     */
    private val controlBoundary = listOf(
        controlBorder on field, controlBorder on surface,
        controlBorder on surfaceFloating, controlBorder on surfaceSunken,
    )

    @Test
    fun `the only boundary of an outlined control meets 3 to 1`() {
        assertContrast("control boundary", 3.0, controlBoundary)
    }

    /**
     * The accent fills, and the label each one carries.
     *
     * This test is the reason `onSunny` exists. Its first version asserted
     * `onAccent` on every accent fill, and two of the fourteen pairs failed:
     *
     *  - light: white `onAccent` on `sunny` (#F2C14E) is **1.68:1**;
     *  - dark: the dark theme's `ink` is a near-white (#F2F0EA) and `sunny` is
     *    the same yellow, so pairing the theme's ink instead collapses to
     *    **1.47:1**.
     *
     * The diagnosis in the failure was that `sunny` is too light to carry any
     * light label in either theme — so the fix is not a workaround in a
     * component but a second label role: `onSunny`, a charcoal, at **10.18:1** on
     * that fill in both themes. A light fill needs a dark label, and that is a
     * property of the fill rather than of the theme, which is exactly why the
     * role is one value and not two.
     *
     * The bug this catches is a new accent arriving without its label, or an
     * existing one being lightened until the label it carries no longer works.
     */
    private val accentFills = listOf(
        onAccent on coral, onAccent on leaf,
        onAccent on lilac, onAccent on deepBlue, onAccent on success,
        role("onSunny") { it.onSunny } on sunny,
    )

    @Test
    fun `every saturated accent fill carries a legible label`() {
        assertContrast("accent fills", 4.5, accentFills)
    }

    // -----------------------------------------------------------------------
    // Invariants
    // -----------------------------------------------------------------------

    /**
     * The audit's inversion: the page is the deeper plane, cards sit on it.
     *
     * This is the relationship that makes a dense screen read as a composition
     * rather than as a spreadsheet, and it is the one relationship an inverted
     * theme would break — the bug this catches is `surface` and `field` being
     * swapped, in either theme, which leaves every contrast pair above passing
     * while the page and the cards flatten into one plane.
     */
    @Test
    fun `a card sits lighter than the page, in both themes`() {
        for ((theme, c) in themes) {
            val card = relativeLuminance(c.surface)
            val page = relativeLuminance(c.field)
            assertTrue(
                "$theme: surface ${c.surface.hex()} (Y=%.4f) must be lighter than field %s (Y=%.4f)"
                    .format(card, c.field.hex(), page),
                card > page,
            )
        }
    }

    /**
     * The instruction band is furniture, in both themes.
     *
     * The audit gives the reason: it is the one element read through a
     * windscreen in direct sunlight, and a light band at the top of a light map
     * has no edge. The bug this catches is a dark theme that "inverts" the
     * band's role — or a light theme that lightens it to match the shell —
     * which is a change no contrast pair above would notice, because white text
     * on a mid-grey band can still clear 4.5:1.
     */
    @Test
    fun `the guidance band is dark in both themes`() {
        for ((theme, c) in themes) {
            for (band in listOf("guidanceSurface" to c.guidanceSurface, "guidanceSurfaceSub" to c.guidanceSurfaceSub)) {
                val y = relativeLuminance(band.second)
                assertTrue(
                    "$theme: ${band.first} ${band.second.hex()} has Y=%.4f; the band must stay dark (Y < 0.06)"
                        .format(y),
                    y < 0.06,
                )
            }
        }
    }

    /**
     * A brand FILL and brand TEXT are different jobs.
     *
     * `primary` carries white (4.95:1) and `primaryText` carries itself against
     * the warm page (7.22:1). The bug this catches is the two being collapsed
     * into one token — the tidy-looking refactor that makes brand text on a card
     * fail AA, or the fill too dark for its own label.
     */
    @Test
    fun `in daylight brand text is deeper than the brand fill`() {
        val fill = relativeLuminance(LightColors.primary)
        val text = relativeLuminance(LightColors.primaryText)
        assertTrue(
            "light: primaryText ${LightColors.primaryText.hex()} (Y=%.4f) must be darker than " +
                "primary ${LightColors.primary.hex()} (Y=%.4f) — the fill carries white, the text " +
                "carries itself on the page".format(text, fill),
            text < fill,
        )
    }

    /**
     * Charcoal, never pure black; cloud, never pure white.
     *
     * The ramp says so ("Charcoal ink. Never pure black; 0x1A is the floor") and
     * the audit measured it in the reference. `onAccent` and `onPrimary` are the
     * two documented exceptions: the label on a saturated fill is pure white in
     * daylight — and stops being white in a theme that has to lighten its fills,
     * which is exactly why the invariant is about the *ramp* rather than about
     * the label.
     *
     * The bug this catches is #000000 or #FFFFFF arriving as an ink, border or
     * surface value — the one change that makes a warm, low-contrast surface
     * system look like a different product.
     */
    @Test
    fun `no shipped role is pure black or pure white except the two label roles`() {
        val documented = setOf("onAccent", "onPrimary")
        val bad = mutableListOf<String>()
        for ((theme, c) in themes) {
            for (r in allRoles) {
                if (r.name in documented) continue
                val v = r.pick(c)
                // `scrim` is translucent: its RGB is a compositing operand over
                // whatever is behind the sheet, not a colour anyone sees.
                if (v.alpha != 1f) continue
                val black = v.red == 0f && v.green == 0f && v.blue == 0f
                val white = v.red == 1f && v.green == 1f && v.blue == 1f
                if (black || white) bad += "$theme: ${r.name} = ${v.hex()}"
            }
        }
        assertTrue("pure black or pure white in: " + bad.joinToString(", "), bad.isEmpty())
    }

    /** Every colour role of [VectorColors], so none is missed by an invariant. */
    private val allRoles = listOf(
        field, surface, surfaceSunken, surfaceFloating,
        border, controlBorder, borderStrong, role("scrim") { it.scrim },
        ink, inkSecondary, inkMuted, inkOnSunken, inkInverse,
        primary, onPrimary, primaryText, primaryContainer, onPrimaryContainer,
        coral, role("coralText") { it.coralText }, role("coralContainer") { it.coralContainer },
        sunny, role("sunnyText") { it.sunnyText }, role("sunnyContainer") { it.sunnyContainer },
        leaf, role("leafText") { it.leafText }, role("leafContainer") { it.leafContainer },
        lilac, role("lilacText") { it.lilacText }, role("lilacContainer") { it.lilacContainer },
        deepBlue, role("deepBlueText") { it.deepBlueText },
        role("deepBlueContainer") { it.deepBlueContainer },
        onAccent, role("onSunny") { it.onSunny },
        success, warning, danger, dangerText, dangerContainer, warningContainer,
        role("infoContainer") { it.infoContainer },
        guidanceSurface, guidanceSurfaceSub, onGuidance, onGuidanceMuted,
        guidanceAccent, guidanceWarn, guidanceDanger,
    )

    /**
     * The role table is the whole palette, not most of it.
     *
     * `VectorColor.kt` states the rule this asserts: a role added without its
     * pair added here is the one way an inaccessible colour gets into the
     * product. Reading the names back from the data class turns "somebody added
     * a role and forgot this file" from a silent hole into a failing test that
     * names the role.
     */
    @Test
    fun `the role table covers every colour the palette declares`() {
        assertEquals(
            "roles declared by VectorColors that this test does not cover",
            declaredRoles,
            allRoles.map { it.name }.toSet(),
        )
    }

    /**
     * The names [VectorColors] declares.
     *
     * `Color` is a value class over a `ULong`, so its getters come back from
     * Java reflection as `long`-returning methods with a mangled name
     * (`getField-0d7_KjU`) — hence the primitive filter and the suffix strip.
     * `isDark` is a Boolean and is not a colour role.
     */
    private val declaredRoles: Set<String> = VectorColors::class.java.methods
        .filter {
            it.parameterCount == 0 && it.returnType == Long::class.javaPrimitiveType && it.name.startsWith("get")
        }
        .map { it.name.removePrefix("get").substringBefore("-").replaceFirstChar { c -> c.lowercase() } }
        .toSet()
}
