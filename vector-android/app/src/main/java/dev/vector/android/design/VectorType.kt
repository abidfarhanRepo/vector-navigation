package dev.vector.android.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.vector.android.R

/**
 * Vector's type system.
 *
 * ## The two families, and why there are two
 *
 * The reference sets its display type in a tight, heavy neo-grotesk and its UI
 * text in the same family a few weights down. That family is licensed and
 * cannot be verified, so it is not reproduced (audit §3). Vector uses two
 * **SIL OFL** families instead, both bundled as static TTFs in `res/font` — no
 * runtime download, no `GoogleFont` provider, no network dependency for basic
 * rendering, which is a hard requirement:
 *
 * * **Manrope** — display, screen titles, section titles and the driving HUD.
 *   Geometric-humanist with flat terminals, a distinctive single-storey `a` in
 *   its heavier weights and slightly closed apertures; set tight and heavy it
 *   produces the reference's confident, editorial headline without copying it.
 *   It also has a tall x-height, which is what makes it survive at 31 sp on a
 *   HUD read at 100 km/h.
 * * **Inter** — everything else. Its numerals are unambiguous, its lowercase
 *   `l`/`I`/`1` are distinguishable, and it holds up under font scaling, which
 *   matters more here than personality does: this app is read in a car, in
 *   sunlight, at 2.0× text size.
 *
 * ## Sizes are a scale, not a list
 *
 * The roles below are the *complete* set. `NavUi.kt` used to carry ~22 bare `sp`
 * literals in its sheets alongside the token scale — a second, undocumented
 * scale living inside the screen. Every one of those is now one of these roles;
 * `design/TypographyUsageTest.kt` fails the build if a bare `sp` literal
 * reappears in a screen file.
 *
 * ## All caps is a single, sanctioned exception
 *
 * The brief forbids all caps except where the brand requires it. Exactly one
 * role does: [VectorTypography.eyebrow], an 11 sp label for section headers
 * (`RECENT`, `SAVED`). It is the one place Vector's own existing UI already
 * used it, and it is tracked out to +0.08 em so it reads as a label rather than
 * as shouting.
 */
object VectorFonts {
    /** Display and titles. See the object KDoc. */
    val display: FontFamily = FontFamily(
        Font(R.font.manrope_800, FontWeight.ExtraBold),
        Font(R.font.manrope_700, FontWeight.Bold),
        Font(R.font.manrope_600, FontWeight.SemiBold),
    )

    /** UI text, body copy, numbers. See the object KDoc. */
    val ui: FontFamily = FontFamily(
        Font(R.font.inter_400, FontWeight.Normal),
        Font(R.font.inter_500, FontWeight.Medium),
        Font(R.font.inter_600, FontWeight.SemiBold),
        Font(R.font.inter_700, FontWeight.Bold),
        Font(R.font.inter_800, FontWeight.ExtraBold),
    )
}

/**
 * The named type roles.
 *
 * Sizes follow the brief's bands: screen title 28–32, section title 20–24, card
 * title 16–18, body 14–16, metadata 12–14, button 15–16. Line heights are
 * generous (1.35–1.5 for running text) because a navigation app is read in
 * glances and a tight leading costs more than it saves.
 */
data class VectorTypography(
    /** Hero copy on onboarding and empty states. Used sparingly, by rule. */
    val display: TextStyle,
    /** A screen's own title: Settings, Your drives. */
    val screenTitle: TextStyle,
    /** A titled group inside a screen. */
    val sectionTitle: TextStyle,
    /** The title of a card, a place row, a list. */
    val cardTitle: TextStyle,
    val body: TextStyle,
    /** Body copy that carries emphasis without changing size. */
    val bodyStrong: TextStyle,
    /** The second line of a two-line row: address, distance, unit. */
    val metadata: TextStyle,
    /** Chip and badge labels. */
    val chip: TextStyle,
    /**
     * The smallest secondary fact: a unit suffix (`km/h`), an attribution, a
     * count beside a glyph.
     *
     * Deliberately NOT [eyebrow]. Both are 11 sp, but a unit suffix set in bold
     * with +0.08 em tracking shouts, and the reference's own unit labels are
     * quiet. The distinction is the reason the two roles exist rather than one.
     */
    val caption: TextStyle,
    val button: TextStyle,
    /** The 11 sp tracked all-caps section label. The one sanctioned caps role. */
    val eyebrow: TextStyle,

    // -- Driving roles ------------------------------------------------------
    /**
     * The maneuver distance and the road name.
     *
     * 31 sp, unchanged from V4's measured value (the audit's Waze cap-height
     * measurement), but now Manrope ExtraBold rather than the platform font —
     * the heaviest, tallest-x-height face in the system, because this is the
     * one string a driver reads at speed.
     */
    val hudPrimary: TextStyle,
    /** Arrival clock, chosen-route duration. */
    val hudSecondary: TextStyle,
    /** The road being driven, alternative durations. */
    val hudContext: TextStyle,
    /** Speed value, lane glyph row. */
    val hudSupporting: TextStyle,
    /** The numeral inside a round dial (speedometer, speed limit). */
    val dialNumber: TextStyle,
    /** The unit line under [dialNumber]. */
    val dialUnit: TextStyle,
)

private fun display(
    size: Int,
    line: Int,
    weight: FontWeight,
    tracking: Double,
) = TextStyle(
    fontFamily = VectorFonts.display,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
)

private fun ui(
    size: Int,
    line: Int,
    weight: FontWeight = FontWeight.Normal,
    tracking: Double = 0.0,
) = TextStyle(
    fontFamily = VectorFonts.ui,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
)

internal val LightTypography: VectorTypography = VectorTypography(
    // Tight tracking on the display sizes is the reference's own behaviour and
    // the reason a heavy grotesk reads as "designed" rather than as "bold".
    display = display(34, 38, FontWeight.ExtraBold, -0.022),
    screenTitle = display(28, 33, FontWeight.ExtraBold, -0.018),
    sectionTitle = display(21, 26, FontWeight.Bold, -0.012),
    cardTitle = ui(17, 23, FontWeight.SemiBold, -0.006),
    body = ui(15, 22),
    bodyStrong = ui(15, 22, FontWeight.SemiBold),
    metadata = ui(13, 18, FontWeight.Medium),
    chip = ui(13, 17, FontWeight.SemiBold, -0.002),
    caption = ui(11, 14, FontWeight.Medium, 0.0),
    button = ui(16, 20, FontWeight.SemiBold, -0.004),
    eyebrow = ui(11, 14, FontWeight.Bold, 0.08),

    hudPrimary = display(31, 36, FontWeight.ExtraBold, -0.02),
    hudSecondary = display(26, 30, FontWeight.Bold, -0.014),
    hudContext = ui(18, 24, FontWeight.SemiBold, -0.006),
    hudSupporting = ui(15, 20, FontWeight.Medium),
    dialNumber = display(26, 28, FontWeight.ExtraBold, -0.02),
    dialUnit = ui(11, 13, FontWeight.SemiBold, 0.04),
)

/**
 * The dark theme shares the light type scale exactly.
 *
 * It is the same data, and that is the point: type is not a theme property.
 * It is defined once and aliased here so `VectorTheme.typography` is total
 * without a screen ever asking which theme it is in.
 */
internal val DarkTypography: VectorTypography = LightTypography

/**
 * A Material 3 [Typography] built from the same family, so any Material
 * component Vector has not replaced (a `Tooltip`, a `Slider` label) inherits
 * Vector's faces instead of Roboto.
 *
 * Material's *sizes* are not used anywhere — only the family binding. A screen
 * that wants Material's `titleLarge` should use [VectorTypography] instead;
 * this exists so that a third-party composable cannot silently introduce a
 * second typeface.
 */
internal fun materialTypographyFrom(t: VectorTypography): Typography = Typography(
    displayLarge = t.display,
    displayMedium = t.display,
    displaySmall = t.display,
    headlineLarge = t.screenTitle,
    headlineMedium = t.screenTitle,
    headlineSmall = t.sectionTitle,
    titleLarge = t.sectionTitle,
    titleMedium = t.cardTitle,
    titleSmall = t.cardTitle,
    bodyLarge = t.body,
    bodyMedium = t.body,
    bodySmall = t.metadata,
    labelLarge = t.button,
    labelMedium = t.chip,
    labelSmall = t.eyebrow,
)
