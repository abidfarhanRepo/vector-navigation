package dev.vector.android.design

import androidx.compose.ui.graphics.Color

/**
 * Vector's colour system.
 *
 * ## Where the values come from
 *
 * Not invented, and not taken from Material. The reference audit
 * (`docs/design/00-reference-audit.md`) sampled Corner's screens at full
 * resolution and measured: a neutral near-white field (`#EEF4`–`#F8F8F8`), cards
 * a shade *lighter* than the page and separated by a hairline plus a soft
 * shadow rather than by contrast, near-black ink that is never pure black, and
 * a single large blue area — the map's water — at a soft sky blue
 * (`#BEDCF4`, `#C5DCF0`, `#CFE5F5`). Saturated colour is reserved almost
 * entirely for pins, status and destructive actions.
 *
 * Vector keeps that *structure* and moves the hue warm, which is the brief's
 * explicit direction ("warm off-white / cloud white surfaces rather than
 * sterile pure-white", "a restrained, desaturated blue range rather than harsh
 * primary blue"). So: the page is a warm cloud (`#F4F1EB`), cards are a warmer
 * white (`#FDFCFA`), ink is charcoal (`#1A1C1E`), and the brand blue is the
 * reference's own water family deepened until it passes AA as text.
 *
 * ## Ramps and roles are different things
 *
 * [Ramp] holds raw values. [VectorColors] holds **roles** — what a colour is
 * for. Screens use roles only; nothing outside this file names a hex. That is
 * what lets the dark theme be *designed* rather than inverted, and it is why
 * the same role can be `#1F6FD0` in daylight and `#5CA1DA` at night without a
 * single call site changing.
 *
 * ## Every text pair here is asserted
 *
 * `design/ContrastTest.kt` computes the WCAG 2.1 ratio for every role pair the
 * UI actually uses and fails the build below 4.5:1 for body text and 3:1 for
 * large text and non-text UI. Adding a role without adding its pair to that
 * test is the one way to get an inaccessible colour into this product, which is
 * why the test enumerates pairs rather than sampling roles.
 */
object Ramp {
    // -- Warm cloud: the surface family. Page → card → raised. -------------
    val cloud00 = Color(0xFFFFFEFC)
    val cloud50 = Color(0xFFFDFCFA)
    val cloud100 = Color(0xFFF9F7F3)
    val cloud200 = Color(0xFFF4F1EB)
    val cloud300 = Color(0xFFEDE9E1)
    val cloud400 = Color(0xFFE2DCD1)
    val cloud500 = Color(0xFFCFC7B9)
    val cloud600 = Color(0xFFAFA69A)

    // -- Charcoal ink. Never pure black; 0x1A is the floor. ----------------
    val ink900 = Color(0xFF1A1C1E)
    val ink800 = Color(0xFF2A2E33)
    val ink700 = Color(0xFF3A4046)
    val ink600 = Color(0xFF4A5158)
    val ink500 = Color(0xFF5F676F)
    val ink400 = Color(0xFF6E767E)
    val ink300 = Color(0xFF98A0A8)
    val ink200 = Color(0xFFC6CCD2)
    val ink100 = Color(0xFFE4E7EA)

    // -- Cerulean: the brand field. 100 is the reference's measured water. --
    val cerulean50 = Color(0xFFEAF3FB)
    val cerulean100 = Color(0xFFCFE5F5)
    val cerulean200 = Color(0xFFB4D6EE)
    val cerulean300 = Color(0xFF8FC0E3)
    val cerulean400 = Color(0xFF5C9BD1)
    val cerulean500 = Color(0xFF3B82C4)
    val cerulean600 = Color(0xFF1F6FD0)
    val cerulean700 = Color(0xFF17559F)
    val cerulean800 = Color(0xFF12406F)
    val cerulean900 = Color(0xFF0C2C4D)

    // -- Semantic accents. Vivid at 300, legible at 600. -------------------
    val coral100 = Color(0xFFFBE4DE)
    val coral300 = Color(0xFFF2836E)
    val coral500 = Color(0xFFE4573F)
    val coral600 = Color(0xFFC2432C)
    val coral700 = Color(0xFFB23A25)
    val coral900 = Color(0xFF5E1B10)

    val sunny100 = Color(0xFFFDF0D5)
    val sunny300 = Color(0xFFF2C14E)
    val sunny500 = Color(0xFFE8A317)
    val sunny700 = Color(0xFF7A5000)
    val sunny900 = Color(0xFF3D2800)

    val leaf100 = Color(0xFFDCF3E3)
    val leaf300 = Color(0xFF5FC98A)
    val leaf500 = Color(0xFF2F8F5B)
    val leaf600 = Color(0xFF1F7A4A)
    val leaf900 = Color(0xFF0C3520)

    val lilac100 = Color(0xFFECE6FA)
    val lilac300 = Color(0xFFA98BE0)
    val lilac500 = Color(0xFF7C6BD1)
    val lilac600 = Color(0xFF5F4FBB)
    val lilac900 = Color(0xFF2A2160)

    val deepBlue300 = Color(0xFF7FB0F0)
    val deepBlue500 = Color(0xFF1B4B8F)
    val deepBlue700 = Color(0xFF10305E)

    // -- Night field. Designed, not derived: the page is a warm-neutral
    //    charcoal (not blue-black), and surfaces LIFT as they nest. --------
    val night000 = Color(0xFF0E1013)
    val night100 = Color(0xFF14161A)
    val night200 = Color(0xFF1D2026)
    val night300 = Color(0xFF242830)
    val night400 = Color(0xFF2E333B)
    val night500 = Color(0xFF3C424B)
    val nightInk = Color(0xFFF2F0EA)
}

/**
 * The semantic roles.
 *
 * Named by what they are for, so a screen never has to know whether it is in
 * daylight. If a role is missing for a surface you are building, add a role —
 * do not reach into [Ramp] from a screen.
 */
data class VectorColors(
    // -- Fields and surfaces ----------------------------------------------
    /** Behind everything: the page field, and the colour the app launches into. */
    val field: Color,
    /** The default card surface. Sits ON [field], a shade lighter. */
    val surface: Color,
    /** A surface nested inside another surface (rows, wells, inset tiles). */
    val surfaceSunken: Color,
    /** A surface that floats above the content plane: sheets, popovers. */
    val surfaceFloating: Color,
    /** The hairline that separates two surfaces. Never a divider inside a row. */
    val border: Color,
    /**
     * The outline of an INTERACTIVE control.
     *
     * Not the same role as [border], and the difference is a WCAG requirement
     * rather than a taste one. [border] is a decorative hairline: it separates
     * two surfaces that are already distinguishable by their own lightness, and
     * the reference audit measured Corner's at roughly 1.2:1 — which is correct
     * for a hairline and would be wrong for a control.
     *
     * An outlined control is different: when it has no fill difference and no
     * shadow, its border is the *only* thing that says "this is a control", and
     * WCAG 2.1 §1.4.11 requires that boundary to reach 3:1 against what it sits
     * on. `design/ContrastTest` asserts exactly that, and it is why this role
     * exists: darkening [border] until it passed would have turned every card's
     * soft hairline into a hard rule and cost the composition the reference's
     * whole surface language.
     */
    val controlBorder: Color,
    /** A stronger border for a control that must read as a control. */
    val borderStrong: Color,
    /** Scrim behind a modal sheet or dialog. */
    val scrim: Color,

    // -- Text --------------------------------------------------------------
    val ink: Color,
    val inkSecondary: Color,
    val inkMuted: Color,
    /** Text on [surfaceSunken] or on a tinted container. */
    val inkOnSunken: Color,
    val inkInverse: Color,

    // -- Brand -------------------------------------------------------------
    /** Filled brand surfaces: the primary CTA, the active chip, the selected pin. */
    val primary: Color,
    val onPrimary: Color,
    /** Brand-coloured TEXT, which needs a deeper value than a brand FILL. */
    val primaryText: Color,
    /** A quiet brand-tinted container (chip rest state, info card). */
    val primaryContainer: Color,
    val onPrimaryContainer: Color,

    // -- Semantic accents --------------------------------------------------
    /** Fills (with [onAccent] text) and, where noted, text. */
    val coral: Color,
    val coralText: Color,
    val coralContainer: Color,
    val sunny: Color,
    val sunnyText: Color,
    val sunnyContainer: Color,
    val leaf: Color,
    val leafText: Color,
    val leafContainer: Color,
    val lilac: Color,
    val lilacText: Color,
    val lilacContainer: Color,
    val deepBlue: Color,
    val deepBlueText: Color,
    val deepBlueContainer: Color,
    /** Text and icons placed on any saturated accent fill above. */
    val onAccent: Color,
    /**
     * The label on [sunny] specifically.
     *
     * Sunny is the one accent that is LIGHT rather than saturated-dark, so a
     * white label on it is 1.68:1 — the single worst pair the design system
     * shipped before this role existed. A light fill needs a dark label, and
     * that is a property of the accent rather than of the theme, so it is
     * stated once here and both themes use the same value.
     */
    val onSunny: Color,

    // -- Status ------------------------------------------------------------
    val success: Color,
    val warning: Color,
    val danger: Color,
    val dangerText: Color,
    val dangerContainer: Color,
    val warningContainer: Color,
    val infoContainer: Color,

    // -- Guidance (the driving HUD) ---------------------------------------
    /**
     * The maneuver band and the strips attached to it.
     *
     * **Dark in both themes, deliberately.** The audit records why: this is the
     * one element read through a windscreen in direct sunlight, and a white
     * band at the top of a light map has no edge. Google Maps makes the same
     * call with a deep teal banner on a light map in both themes. What changes
     * between themes is the map and the panels; the instruction band is
     * furniture, like a road sign.
     */
    val guidanceSurface: Color,
    val guidanceSurfaceSub: Color,
    val onGuidance: Color,
    val onGuidanceMuted: Color,
    val guidanceAccent: Color,
    /** The over-limit / warning treatment ON the guidance band. */
    val guidanceWarn: Color,
    val guidanceDanger: Color,

    /** Whether this theme is the dark one. Used only for system-bar polarity. */
    val isDark: Boolean,
)

/**
 * Daylight — the primary design target.
 *
 * Relationships, in order of importance:
 *  1. [VectorColors.surface] is *lighter* than [VectorColors.field]; the page is
 *     the deeper plane and cards sit on it. This is the reference's own
 *     inversion of the usual convention and it is what makes a dense screen of
 *     cards read as a composition rather than as a spreadsheet.
 *  2. Saturated colour is scarce. Accents appear on pins, chips, status and
 *     destructive actions; everything else is cloud and charcoal.
 *  3. Text colours are chosen for contrast first, hue second — `primaryText`
 *     (`#17559F`) is a full step deeper than the `primary` fill (`#1F6FD0`)
 *     because the fill only ever carries white, while the text carries the
 *     brand against a warm white page.
 */
internal val LightColors = VectorColors(
    field = Ramp.cloud200,
    surface = Ramp.cloud50,
    surfaceSunken = Ramp.cloud300,
    surfaceFloating = Ramp.cloud00,
    border = Ramp.cloud400,
    // 3.40:1 on the field, 3.74:1 on a card — see the role's KDoc.
    controlBorder = Color(0xFF8A8174),
    borderStrong = Ramp.cloud500,
    scrim = Color(0x661A1C1E),

    ink = Ramp.ink900,
    inkSecondary = Ramp.ink600,
    inkMuted = Ramp.ink500,
    inkOnSunken = Ramp.ink600,
    inkInverse = Ramp.cloud00,

    primary = Ramp.cerulean600,
    onPrimary = Color(0xFFFFFFFF),
    primaryText = Ramp.cerulean700,
    primaryContainer = Ramp.cerulean100,
    onPrimaryContainer = Ramp.cerulean800,

    coral = Ramp.coral600,
    coralText = Ramp.coral700,
    coralContainer = Ramp.coral100,
    sunny = Ramp.sunny300,
    sunnyText = Ramp.sunny700,
    sunnyContainer = Ramp.sunny100,
    leaf = Ramp.leaf600,
    leafText = Ramp.leaf600,
    leafContainer = Ramp.leaf100,
    lilac = Ramp.lilac600,
    lilacText = Ramp.lilac600,
    lilacContainer = Ramp.lilac100,
    deepBlue = Ramp.deepBlue500,
    deepBlueText = Ramp.deepBlue500,
    deepBlueContainer = Ramp.cerulean100,
    onAccent = Color(0xFFFFFFFF),
    onSunny = Ramp.ink900,

    success = Ramp.leaf600,
    warning = Ramp.sunny700,
    danger = Ramp.coral600,
    dangerText = Ramp.coral700,
    dangerContainer = Ramp.coral100,
    warningContainer = Ramp.sunny100,
    infoContainer = Ramp.cerulean100,

    guidanceSurface = Color(0xFF11161F),
    guidanceSurfaceSub = Color(0xFF1D232F),
    onGuidance = Color(0xFFF7F9FC),
    onGuidanceMuted = Color(0xFFB6C2D2),
    guidanceAccent = Ramp.cerulean400,
    guidanceWarn = Color(0xFFFFC24B),
    guidanceDanger = Color(0xFFFF7A6B),

    isDark = false,
)

/**
 * Night — designed, not inverted.
 *
 * The field is a warm-neutral charcoal rather than a blue-black, so the map's
 * own dark cartography does not fight the chrome for the same hue. Surfaces
 * *lift* as they nest (page → card → raised), which is the daylight
 * relationship read in the other direction, and no role is a straight
 * arithmetic inversion of its light counterpart: the accents are lightened and
 * desaturated until they carry the same weight against a dark field that the
 * daylight accents carry against a light one.
 */
internal val DarkColors = VectorColors(
    field = Ramp.night100,
    surface = Ramp.night200,
    surfaceSunken = Ramp.night000,
    surfaceFloating = Ramp.night300,
    border = Ramp.night400,
    // 3.94:1 on the field, 3.55:1 on a card.
    controlBorder = Color(0xFF6E7681),
    borderStrong = Ramp.night500,
    scrim = Color(0x99000000),

    ink = Ramp.nightInk,
    inkSecondary = Color(0xFFB9BFC7),
    inkMuted = Color(0xFF8B939C),
    inkOnSunken = Color(0xFFA8B0B8),
    inkInverse = Ramp.night100,

    primary = Ramp.cerulean400,
    onPrimary = Color(0xFF0B1017),
    primaryText = Ramp.deepBlue300,
    primaryContainer = Color(0xFF17324F),
    onPrimaryContainer = Ramp.cerulean100,

    coral = Ramp.coral300,
    coralText = Ramp.coral300,
    coralContainer = Color(0xFF3A1A14),
    sunny = Ramp.sunny300,
    sunnyText = Ramp.sunny300,
    sunnyContainer = Color(0xFF3A2E12),
    leaf = Ramp.leaf300,
    leafText = Ramp.leaf300,
    leafContainer = Color(0xFF12301F),
    lilac = Ramp.lilac300,
    lilacText = Ramp.lilac300,
    lilacContainer = Color(0xFF241C42),
    deepBlue = Ramp.deepBlue300,
    deepBlueText = Ramp.deepBlue300,
    deepBlueContainer = Color(0xFF14263F),
    onAccent = Color(0xFF0B1017),
    // The same value in both themes: a light fill needs a dark label whatever
    // the app's theme is, because the fill does not change.
    onSunny = Ramp.ink900,

    success = Ramp.leaf300,
    warning = Ramp.sunny300,
    danger = Ramp.coral300,
    dangerText = Ramp.coral300,
    dangerContainer = Color(0xFF3A1A14),
    warningContainer = Color(0xFF3A2E12),
    infoContainer = Color(0xFF17324F),

    guidanceSurface = Color(0xFF080A10),
    guidanceSurfaceSub = Color(0xFF141821),
    onGuidance = Color(0xFFF7F9FC),
    onGuidanceMuted = Color(0xFFAEBACB),
    guidanceAccent = Ramp.cerulean300,
    guidanceWarn = Color(0xFFFFC24B),
    guidanceDanger = Color(0xFFFF7A6B),

    isDark = true,
)

// ---------------------------------------------------------------------------
// Contrast maths
// ---------------------------------------------------------------------------

/**
 * WCAG 2.1 relative luminance.
 *
 * Lives in production code rather than in the test because the *system* owns
 * the rule and the test only asserts it; a test that re-implements the formula
 * would pass while the shipped palette drifted, which is the failure mode this
 * whole file exists to prevent.
 */
internal fun relativeLuminance(c: Color): Double {
    fun channel(v: Float): Double {
        val s = v.toDouble()
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
}

/** WCAG 2.1 contrast ratio, 1.0 (identical) to 21.0 (black on white). */
internal fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return (hi + 0.05) / (lo + 0.05)
}
